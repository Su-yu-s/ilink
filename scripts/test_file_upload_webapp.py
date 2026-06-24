import os
from pathlib import Path

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("WEBAPP_BASE_URL", "http://127.0.0.1:8090")
UPLOAD_DIR = Path(os.environ["FILE_UPLOAD_DIR"])


def main():
    probe_dir = UPLOAD_DIR / "images"
    probe_dir.mkdir(parents=True, exist_ok=True)
    probe_file = probe_dir / "webapp-probe.txt"
    probe_file.write_text("ilink-upload-static-ok", encoding="utf-8")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        static_response = page.goto(f"{BASE_URL}/uploads/images/webapp-probe.txt")
        page.wait_for_load_state("networkidle")
        static_body = page.text_content("body")
        assert static_response is not None
        assert static_response.status == 200, f"静态资源状态异常: {static_response.status}"
        assert "ilink-upload-static-ok" in (static_body or ""), "静态资源内容不匹配"

        api_response = page.request.post(
            f"{BASE_URL}/api/files/upload",
            multipart={
                "bizType": "images",
                "file": {
                    "name": "probe.png",
                    "mimeType": "image/png",
                    "buffer": b"\x89PNG\r\n\x1a\n\x00\x00\x00\x00",
                },
            },
        )
        api_text = api_response.text()
        protected = (
            api_response.status in (302, 403)
            or api_response.url.endswith("/login")
            or "login" in api_response.url.lower()
            or "\u767b\u5f55" in api_text
            or "用户名" in api_text
        )
        assert protected, (
            f"未登录上传未被保护: status={api_response.status}, url={api_response.url}, body={api_text[:120]}"
        )

        browser.close()

    print("静态资源匿名访问通过: 200")
    print(f"未登录上传接口安全保护通过: status={api_response.status}, url={api_response.url}")


if __name__ == "__main__":
    main()
