import base64
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import expect, sync_playwright


BASE_URL = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:18090")
PASSWORD = "Aa12345678"


def make_webp_named_jpg() -> Path:
    tmp_dir = Path(__file__).resolve().parent / ".tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    file_path = tmp_dir / "avatar-content-webp-named-jpg.jpg"
    # 1x1 WebP。文件名故意使用 .jpg，用来验证后端按真实内容保存为 .webp。
    data = base64.b64decode(
        "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAQAcJaQAA3AA/vuUAAA="
    )
    file_path.write_bytes(data)
    return file_path


def wait_for_toast(page, expected_text: str):
    toast = page.locator(".ilink-toast__text", has_text=expected_text).last
    expect(toast).to_be_visible(timeout=5000)
    return toast


def wait_for_http_ready(timeout_seconds: int = 90):
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{BASE_URL}/", timeout=3) as response:
                if response.status < 500:
                    return
        except (OSError, urllib.error.URLError) as exc:
            last_error = exc
        time.sleep(1)
    raise TimeoutError(f"应用未在 {timeout_seconds} 秒内完成 HTTP 就绪: {last_error}")


def register_and_login(page, student_id: str):
    page.goto(f"{BASE_URL}/register", timeout=60000)
    page.wait_for_load_state("networkidle")
    page.click("#toggleStudentId")
    page.fill("#studentId", student_id)
    page.fill("#password", PASSWORD)
    page.fill("#confirmPassword", PASSWORD)
    page.check("#agreement")

    with page.expect_response(lambda r: "/api/register" in r.url and r.request.method == "POST") as reg_info:
        page.click("#registerBtn")
    reg_response = reg_info.value
    assert reg_response.ok, f"注册接口 HTTP 失败: {reg_response.status}"
    reg_payload = reg_response.json()
    assert reg_payload.get("code") == 200, f"注册失败: {json.dumps(reg_payload, ensure_ascii=False)}"

    page.goto(f"{BASE_URL}/login")
    page.wait_for_load_state("networkidle")
    page.fill("#identifier", student_id)
    page.fill("#password", PASSWORD)

    with page.expect_response(lambda r: "/api/login" in r.url and r.request.method == "POST") as login_info:
        page.click("#loginBtn")
    login_response = login_info.value
    assert login_response.ok, f"登录接口 HTTP 失败: {login_response.status}"
    login_payload = login_response.json()
    assert login_payload.get("code") == 200, f"登录失败: {json.dumps(login_payload, ensure_ascii=False)}"


def run():
    student_id = str(int(time.time() * 1000))[-12:]
    avatar_file = make_webp_named_jpg()

    with sync_playwright() as p:
        wait_for_http_ready()
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1366, "height": 900})

        console_errors = []
        page_errors = []
        http_errors = []
        page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
        page.on("pageerror", lambda exc: page_errors.append(str(exc)))
        page.on(
            "response",
            lambda resp: http_errors.append(f"{resp.status} {resp.url}")
            if resp.status >= 400
            else None,
        )

        try:
            register_and_login(page, student_id)

            page.goto(f"{BASE_URL}/profile-edit.html")
            page.wait_for_load_state("networkidle")
            expect(page.locator("#profileForm")).to_be_visible(timeout=5000)
            expect(page.locator("#profileSaveBtn")).to_be_enabled(timeout=5000)
            page.wait_for_timeout(500)

            with page.expect_response(lambda r: "/api/files/upload" in r.url and r.request.method == "POST") as upload_info:
                page.set_input_files("#avatarFileInput", str(avatar_file))
            upload_response = upload_info.value
            assert upload_response.ok, f"头像上传接口 HTTP 失败: {upload_response.status}"
            upload_payload = upload_response.json()
            assert upload_payload.get("code") == 200, f"头像上传失败: {json.dumps(upload_payload, ensure_ascii=False)}"
            avatar_url = upload_payload.get("data")
            assert isinstance(avatar_url, str) and avatar_url.endswith(".webp"), (
                f"WebP 内容应保存为 .webp，实际返回: {avatar_url}"
            )
            expect(page.locator("#avatarUploadHint")).to_contain_text("上传成功", timeout=5000)

            profile_posts = []
            page.on(
                "request",
                lambda req: profile_posts.append(req.url)
                if "/api/user/profile" in req.url and req.method == "POST"
                else None,
            )

            page.fill("#username", f"e2e用户{student_id}")
            page.fill("#email", "3353493269qq.com")
            page.click("#profileSaveBtn")
            wait_for_toast(page, "请输入正确的邮箱地址")
            assert page.locator("#profileForm").evaluate("el => el.classList.contains('was-validated')")
            active_id = page.evaluate("document.activeElement && document.activeElement.id")
            assert active_id == "email", f"邮箱非法时应聚焦邮箱输入框，实际焦点: {active_id}"
            page.wait_for_timeout(300)
            assert not profile_posts, "邮箱非法时不应发送 /api/user/profile 保存请求"

            page.fill("#email", "3353493269@qq.com")
            with page.expect_response(lambda r: "/api/user/profile" in r.url and r.request.method == "POST") as save_info:
                page.click("#profileSaveBtn")
            save_response = save_info.value
            assert save_response.ok, f"资料保存接口 HTTP 失败: {save_response.status}"
            save_payload = save_response.json()
            assert save_payload.get("code") == 200, f"资料保存失败: {json.dumps(save_payload, ensure_ascii=False)}"
            wait_for_toast(page, "保存成功")

            if page_errors:
                raise AssertionError("页面 JS 异常: " + " | ".join(page_errors))
            if http_errors:
                raise AssertionError("页面存在 HTTP 错误: " + " | ".join(http_errors))
            if console_errors:
                raise AssertionError("浏览器 console error: " + " | ".join(console_errors))
        except Exception:
            screenshot = Path(__file__).resolve().parent / ".tmp" / "profile-edit-validation-failure.png"
            screenshot.parent.mkdir(parents=True, exist_ok=True)
            try:
                page.screenshot(path=str(screenshot), full_page=True)
                print(f"失败截图: {screenshot}")
            except PlaywrightTimeoutError:
                pass
            raise
        finally:
            browser.close()


if __name__ == "__main__":
    run()
