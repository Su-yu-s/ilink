import os
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("WEBAPP_BASE_URL", "http://127.0.0.1:8090").rstrip("/")
USERNAME = os.environ["ILINK_TEST_USERNAME"]
PASSWORD = os.environ["ILINK_TEST_PASSWORD"]


def cookie_value(context, name):
    parsed = urlparse(BASE_URL)
    target_host = parsed.hostname or ""
    for cookie in context.cookies():
        if cookie["name"] == name and target_host.endswith(cookie["domain"].lstrip(".")):
            return cookie["value"]
    return None


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


def assert_json_ok(response, label):
    text = response.text()
    assert response.ok, f"{label} HTTP 状态异常: {response.status}, body={text[:200]}"
    data = response.json()
    assert data.get("code") == 200, f"{label} 业务失败: {data}"
    return data


def main():
    png_bytes = (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
        b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00"
        b"\x90wS\xde\x00\x00\x00\x0cIDAT\x08\xd7c\xf8\xff\xff?\x00\x05\xfe\x02\xfeA\xe2!\xbc"
        b"\x00\x00\x00\x00IEND\xaeB`\x82"
    )

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()

        page.goto(f"{BASE_URL}/login", wait_until="networkidle")
        token = cookie_value(context, "XSRF-TOKEN")
        assert token, "未获取到 CSRF Token"

        login = page.request.post(
            f"{BASE_URL}/api/login",
            headers={"Content-Type": "application/json", "X-XSRF-TOKEN": token},
            data={"identifier": USERNAME, "password": PASSWORD},
        )
        assert_json_ok(login, "登录")

        current = assert_json_ok(page.request.get(f"{BASE_URL}/api/user/profile"), "获取资料")["data"]
        original_avatar = current.get("avatar") or ""
        expected_initial = (current.get("username") or "").strip()[:1].upper()
        assert expected_initial, "用户名为空，无法验证头像首字"

        token = cookie_value(context, "XSRF-TOKEN") or token
        clear = page.request.post(
            f"{BASE_URL}/api/user/profile",
            headers={"Content-Type": "application/json", "X-XSRF-TOKEN": token},
            data=profile_payload(current, ""),
        )
        assert_json_ok(clear, "清空头像")

        page.goto(f"{BASE_URL}/profile-edit.html", wait_until="networkidle")
        page.wait_for_selector("#avatarFallback:not(.il-avatar-fallback--hidden)")
        edit_initial = page.locator("#avatarFallback").text_content().strip()
        header_initial = page.locator(".il-header__avatar-fallback").first.text_content().strip()
        assert edit_initial == expected_initial, f"编辑页头像首字不一致: {edit_initial} != {expected_initial}"
        assert header_initial == expected_initial, f"顶部头像首字不一致: {header_initial} != {expected_initial}"

        page.set_input_files(
            "#avatarFileInput",
            files=[{"name": "avatar-ui-probe.png", "mimeType": "image/png", "buffer": png_bytes}],
        )
        page.wait_for_function(
            "() => document.querySelector('#avatarPreviewImg')?.src.includes('/uploads/avatars/')",
            timeout=15000,
        )
        avatar_url = page.locator("#avatar").input_value()
        assert "/uploads/avatars/" in avatar_url, f"上传后的头像 URL 异常: {avatar_url}"
        assert page.locator("#avatarPreviewImg").is_visible(), "上传后编辑页头像图片未显示"

        page.locator("#profileSaveBtn").click()
        page.wait_for_timeout(1200)
        saved = assert_json_ok(page.request.get(f"{BASE_URL}/api/user/profile"), "保存后获取资料")["data"]
        assert saved.get("avatar") == avatar_url, f"保存后的头像未写入资料: {saved.get('avatar')} != {avatar_url}"

        static_response = page.goto(avatar_url, wait_until="networkidle")
        assert static_response is not None and static_response.status == 200, "上传头像静态访问失败"

        token = cookie_value(context, "XSRF-TOKEN") or token
        restore = page.request.post(
            f"{BASE_URL}/api/user/profile",
            headers={"Content-Type": "application/json", "X-XSRF-TOKEN": token},
            data=profile_payload(saved, original_avatar),
        )
        assert_json_ok(restore, "恢复头像")
        browser.close()

    print(f"fallback 首字统一通过: {expected_initial}")
    print(f"编辑页上传预览通过: {avatar_url}")
    print("保存头像并公开访问通过: 200")
    print("已恢复测试前头像值")


if __name__ == "__main__":
    main()
