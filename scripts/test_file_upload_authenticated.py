import os
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("WEBAPP_BASE_URL", "http://121.40.34.68:8090").rstrip("/")
USERNAME = os.environ["ILINK_TEST_USERNAME"]
PASSWORD = os.environ["ILINK_TEST_PASSWORD"]


def cookie_value(context, name):
    parsed = urlparse(BASE_URL)
    target_host = parsed.hostname or ""
    for cookie in context.cookies():
        if cookie["name"] == name and target_host.endswith(cookie["domain"].lstrip(".")):
            return cookie["value"]
    return None


def main():
    png_bytes = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00"

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()

        page.goto(f"{BASE_URL}/login", wait_until="networkidle")
        assert "login" in page.url.lower(), f"登录页地址异常: {page.url}"

        token = cookie_value(context, "XSRF-TOKEN")
        assert token, "未获取到 CSRF Token"

        login_response = page.request.post(
            f"{BASE_URL}/api/login",
            headers={
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": token,
            },
            data={
                "identifier": USERNAME,
                "password": PASSWORD,
            },
        )
        login_json = login_response.json()
        assert login_response.ok, f"登录 HTTP 状态异常: {login_response.status}"
        assert login_json.get("code") == 200, f"登录失败: {login_json}"

        upload_token = cookie_value(context, "XSRF-TOKEN") or token
        upload_response = page.request.post(
            f"{BASE_URL}/api/files/upload",
            headers={"X-XSRF-TOKEN": upload_token},
            multipart={
                "bizType": "images",
                "file": {
                    "name": "playwright-probe.png",
                    "mimeType": "image/png",
                    "buffer": png_bytes,
                },
            },
        )
        upload_text = upload_response.text()
        assert upload_response.ok, (
            f"上传 HTTP 状态异常: status={upload_response.status}, url={upload_response.url}, body={upload_text[:300]}"
        )
        upload_json = upload_response.json()
        assert upload_json.get("code") == 200, f"上传失败: {upload_json}"
        file_url = upload_json.get("data")
        assert isinstance(file_url, str) and file_url.startswith(f"{BASE_URL}/uploads/images/"), file_url

        static_response = page.goto(file_url, wait_until="networkidle")
        assert static_response is not None
        assert static_response.status == 200, f"上传后访问状态异常: {static_response.status}"

        browser.close()

    print("登录成功")
    print(f"上传成功: {file_url}")
    print("上传文件公开访问通过: 200")


if __name__ == "__main__":
    main()
