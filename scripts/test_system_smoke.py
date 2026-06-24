import os
import re
import time
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("WEBAPP_BASE_URL", "http://127.0.0.1:8090").rstrip("/")
USERNAME = os.environ["ILINK_TEST_USERNAME"]
PASSWORD = os.environ["ILINK_TEST_PASSWORD"]
OUTPUT_DIR = Path(os.environ.get("SMOKE_OUTPUT_DIR", "screenshots/system-smoke"))
ACTUAL_AVATAR_PATH = os.environ.get("ILINK_ACTUAL_AVATAR_PATH")

PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
    b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00"
    b"\x90wS\xde\x00\x00\x00\x0cIDAT\x08\xd7c\xf8\xff\xff?"
    b"\x00\x05\xfe\x02\xfeA\xe2!\xbc\x00\x00\x00\x00IEND\xaeB`\x82"
)
PDF_BYTES = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"


def cookie_value(context, name):
    parsed = urlparse(BASE_URL)
    target_host = parsed.hostname or ""
    for cookie in context.cookies():
        if cookie["name"] == name and target_host.endswith(cookie["domain"].lstrip(".")):
            return cookie["value"]
    return None


def assert_json_ok(response, label):
    text = response.text()
    assert response.ok, f"{label} HTTP 状态异常: {response.status}, body={text[:300]}"
    data = response.json()
    assert data.get("code") == 200, f"{label} 业务失败: {data}"
    return data


def profile_payload(user, avatar):
    return {
        "username": user.get("username") or "",
        "email": user.get("email") or "",
        "realName": user.get("realName") or "",
        "avatar": avatar,
        "gender": user.get("gender") or "",
        "grade": user.get("grade") or "",
        "major": user.get("major") or "",
        "school": user.get("school") or "",
        "college": user.get("college") or "",
        "bio": user.get("bio") or "",
    }


def update_profile_avatar(page, context, user, avatar, label):
    token = cookie_value(context, "XSRF-TOKEN")
    response = page.request.post(
        f"{BASE_URL}/api/user/profile",
        headers={"Content-Type": "application/json", "X-XSRF-TOKEN": token or ""},
        data=profile_payload(user, avatar),
    )
    return assert_json_ok(response, label)


def visible_texts(page, selector):
    return page.locator(selector).evaluate_all(
        """els => els
        .filter(el => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
                && style.visibility !== 'hidden'
                && !el.className.includes('hidden')
                && rect.width > 0
                && rect.height > 0;
        })
        .map(el => (el.textContent || '').trim())
        .filter(Boolean)
        """
    )


def visible_attrs(page, selector, attr):
    return page.locator(selector).evaluate_all(
        """(els, attr) => els
        .filter(el => {
            const style = window.getComputedStyle(el);
            const rect = el.getBoundingClientRect();
            return style.display !== 'none'
                && style.visibility !== 'hidden'
                && rect.width > 0
                && rect.height > 0;
        })
        .map(el => el.getAttribute(attr) || '')
        .filter(Boolean)
        """,
        attr,
    )


class PageMonitor:
    def __init__(self, page):
        self.console_errors = []
        self.page_errors = []
        self.bad_responses = []
        page.on("console", self._on_console)
        page.on("pageerror", lambda exc: self.page_errors.append(str(exc)))
        page.on("response", self._on_response)

    def clear(self):
        self.console_errors.clear()
        self.page_errors.clear()
        self.bad_responses.clear()

    def _on_console(self, msg):
        if msg.type == "error":
            location = msg.location
            url = location.get("url") if isinstance(location, dict) else ""
            detail = msg.text
            if url:
                detail = f"{detail} ({url})"
            self.console_errors.append(detail)

    def _on_response(self, response):
        status = response.status
        if status < 400:
            return
        request = response.request
        url = response.url
        if url.endswith("/favicon.ico"):
            return
        resource_type = request.resource_type
        if resource_type in ("document", "script", "stylesheet", "image", "font") or status >= 500:
            self.bad_responses.append(f"{status} {resource_type} {url}")

    def assert_clean(self, label, allow_url_parts=None):
        allow_url_parts = allow_url_parts or []

        def allowed(item):
            return any(part in item for part in allow_url_parts)

        console_errors = [item for item in self.console_errors if not allowed(item)]
        page_errors = [item for item in self.page_errors if not allowed(item)]
        bad_responses = [item for item in self.bad_responses if not allowed(item)]
        assert not bad_responses, f"{label} 存在资源/接口异常: {bad_responses[:5]}"
        assert not console_errors, f"{label} 存在控制台错误: {console_errors[:3]}"
        assert not page_errors, f"{label} 存在页面脚本异常: {page_errors[:3]}"


def visit_page(page, monitor, path, label, screenshot_name=None, allow_url_parts=None):
    monitor.clear()
    response = page.goto(f"{BASE_URL}{path}", wait_until="networkidle", timeout=30000)
    assert response is not None, f"{label} 未返回响应"
    assert response.status < 400, f"{label} 页面状态异常: {response.status}"
    page.wait_for_timeout(300)
    if screenshot_name:
        page.screenshot(path=str(OUTPUT_DIR / screenshot_name), full_page=True)
    monitor.assert_clean(label, allow_url_parts=allow_url_parts)


def login(page, context):
    page.goto(f"{BASE_URL}/login", wait_until="networkidle")
    token = cookie_value(context, "XSRF-TOKEN")
    assert token, "未获取到 CSRF Token"
    response = page.request.post(
        f"{BASE_URL}/api/login",
        headers={"Content-Type": "application/json", "X-XSRF-TOKEN": token},
        data={"identifier": USERNAME, "password": PASSWORD},
    )
    assert_json_ok(response, "登录")
    return assert_json_ok(page.request.get(f"{BASE_URL}/api/user/profile"), "获取用户资料")["data"]


def upload_file(page, context, biz_type, name, mime_type, payload, expected_path, label):
    token = cookie_value(context, "XSRF-TOKEN")
    response = page.request.post(
        f"{BASE_URL}/api/files/upload",
        headers={"X-XSRF-TOKEN": token or ""},
        multipart={
            "bizType": biz_type,
            "file": {
                "name": name,
                "mimeType": mime_type,
                "buffer": payload,
            },
        },
    )
    data = assert_json_ok(response, label)
    url = data.get("data")
    assert isinstance(url, str), f"{label} 未返回 URL: {data}"
    assert f"/uploads/{expected_path}/" in url, f"{label} URL 目录异常: {url}"
    static_response = page.request.get(url)
    assert static_response.status == 200, f"{label} 静态访问失败: {static_response.status}, url={url}"
    return url


def assert_bad_upload(page, context, multipart, label):
    token = cookie_value(context, "XSRF-TOKEN")
    response = page.request.post(
        f"{BASE_URL}/api/files/upload",
        headers={"X-XSRF-TOKEN": token or ""},
        multipart=multipart,
    )
    text = response.text()
    assert response.status == 400, f"{label} HTTP 状态应为 400，实际 {response.status}: {text[:200]}"
    data = response.json()
    assert data.get("code") == 400, f"{label} 业务 code 应为 400: {data}"


def assert_fallback_text(page, selector, expected, label):
    texts = visible_texts(page, selector)
    assert expected in texts, f"{label} 未显示期望首字 {expected}，实际可见文本: {texts}"


def assert_visible_image_src(page, selector, expected_url, label):
    srcs = visible_attrs(page, selector, "src")
    assert any(src == expected_url for src in srcs), f"{label} 未显示头像图片: {expected_url}, 实际: {srcs}"


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    results = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        page = context.new_page()
        monitor = PageMonitor(page)

        current = None
        original_avatar = ""
        try:
            current = login(page, context)
            original_avatar = current.get("avatar") or ""
            expected_initial = (current.get("username") or "").strip()[:1].upper()
            assert expected_initial, "用户名为空，无法验证头像首字一致性"
            results.append(f"登录成功，期望头像首字: {expected_initial}")

            update_profile_avatar(page, context, current, "", "清空头像")
            results.append("已清空测试账号头像，开始无头像基线检查")

            for path, name in [
                ("/index.html", "home.png"),
                ("/team-market.html", "team-market.png"),
                ("/teacher-wall.html", "teacher-wall.png"),
                ("/community.html", "community.png"),
                ("/competitions.html", "competitions.png"),
                ("/gallery.html", "gallery.png"),
                ("/profile.html", "profile.png"),
                ("/profile-edit.html", "profile-edit.png"),
            ]:
                visit_page(page, monitor, path, path, screenshot_name=name)
            results.append("主要页面访问、控制台错误、静态资源检查通过")

            visit_page(page, monitor, "/profile.html", "个人中心无头像状态", screenshot_name="profile-empty-avatar.png")
            assert_fallback_text(page, ".il-header__avatar-fallback", expected_initial, "顶部头像")
            assert_fallback_text(page, ".il-profile-avatar-fallback", expected_initial, "个人中心头像")

            visit_page(page, monitor, "/profile-edit.html", "编辑资料无头像状态", screenshot_name="profile-edit-empty-avatar.png")
            assert_fallback_text(page, ".il-header__avatar-fallback", expected_initial, "顶部头像")
            assert_fallback_text(page, "#avatarFallback", expected_initial, "编辑页头像")
            results.append("个人中心与编辑资料页无头像 fallback 首字一致")

            missing_avatar = f"{BASE_URL}/uploads/avatars/system-smoke-not-found.png"
            update_profile_avatar(page, context, current, missing_avatar, "设置缺失头像")
            visit_page(
                page,
                monitor,
                "/profile-edit.html",
                "编辑资料缺失头像状态",
                screenshot_name="profile-edit-missing-avatar.png",
                allow_url_parts=["system-smoke-not-found.png"],
            )
            assert_fallback_text(page, ".il-header__avatar-fallback", expected_initial, "缺失图片时顶部头像")
            assert_fallback_text(page, "#avatarFallback", expected_initial, "缺失图片时编辑页头像")
            results.append("头像 URL 失效时 fallback 回退一致")

            page.set_input_files(
                "#avatarFileInput",
                files=[{"name": "avatar-smoke.png", "mimeType": "image/png", "buffer": PNG_BYTES}],
            )
            page.wait_for_function(
                "() => document.querySelector('#avatarPreviewImg')?.src.includes('/uploads/avatars/')",
                timeout=15000,
            )
            avatar_url = page.locator("#avatar").input_value()
            assert "/uploads/avatars/" in avatar_url, f"上传头像 URL 异常: {avatar_url}"
            assert page.locator("#avatarPreviewImg").is_visible(), "上传后编辑页头像预览未显示"
            page.locator("#profileSaveBtn").click()
            for _ in range(12):
                saved = assert_json_ok(page.request.get(f"{BASE_URL}/api/user/profile"), "读取保存后资料")["data"]
                if saved.get("avatar") == avatar_url:
                    current = saved
                    break
                time.sleep(0.5)
            else:
                raise AssertionError("保存头像后资料未更新")

            visit_page(page, monitor, "/profile.html", "个人中心头像图片状态", screenshot_name="profile-uploaded-avatar.png")
            assert_visible_image_src(page, ".il-header__avatar", avatar_url, "顶部头像")
            assert_visible_image_src(page, ".il-profile-avatar img", avatar_url, "个人中心头像")
            results.append("编辑页上传头像、保存、个人中心展示通过")

            if ACTUAL_AVATAR_PATH:
                actual_avatar = Path(ACTUAL_AVATAR_PATH)
                assert actual_avatar.exists(), f"真实头像测试文件不存在: {actual_avatar}"
                visit_page(page, monitor, "/profile-edit.html", "真实头像文件上传前")
                page.set_input_files("#avatarFileInput", str(actual_avatar))
                page.wait_for_function(
                    "() => document.querySelector('#avatarPreviewImg')?.src.includes('/uploads/avatars/')",
                    timeout=15000,
                )
                actual_avatar_url = page.locator("#avatar").input_value()
                assert "/uploads/avatars/" in actual_avatar_url, f"真实头像上传 URL 异常: {actual_avatar_url}"
                actual_static = page.request.get(actual_avatar_url)
                assert actual_static.status == 200, (
                    f"真实头像静态访问失败: {actual_static.status}, url={actual_avatar_url}"
                )
                results.append(f"真实头像文件上传通过: {actual_avatar.name}")

            upload_file(page, context, "images", "image-smoke.png", "image/png", PNG_BYTES, "images", "普通图片上传")
            upload_file(page, context, "certificates", "certificate-smoke.pdf", "application/pdf", PDF_BYTES, "certificates", "证书 PDF 上传")
            results.append("三类上传中的 images/certificates API 与公开访问通过")

            assert_bad_upload(
                page,
                context,
                {
                    "bizType": "../avatars",
                    "file": {"name": "bad.png", "mimeType": "image/png", "buffer": PNG_BYTES},
                },
                "非法 bizType",
            )
            assert_bad_upload(
                page,
                context,
                {
                    "bizType": "avatars",
                    "file": {"name": "../evil.png", "mimeType": "image/png", "buffer": PNG_BYTES},
                },
                "路径穿越文件名",
            )
            assert_bad_upload(
                page,
                context,
                {
                    "bizType": "images",
                    "file": {"name": "fake.png", "mimeType": "image/png", "buffer": b"not-a-real-png"},
                },
                "伪造图片内容",
            )
            assert_bad_upload(
                page,
                context,
                {
                    "bizType": "avatars",
                    "file": {"name": "avatar.pdf", "mimeType": "application/pdf", "buffer": PDF_BYTES},
                },
                "头像不允许 PDF",
            )
            results.append("非法上传拦截通过")

        finally:
            if current is not None:
                update_profile_avatar(page, context, current, original_avatar, "恢复测试前头像")
            browser.close()

    print("\n".join(f"- {item}" for item in results))
    print(f"截图目录: {OUTPUT_DIR.resolve()}")


if __name__ == "__main__":
    main()
