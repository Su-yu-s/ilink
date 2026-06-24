"""
E2E: 全站统一搜索框 il-search-field 可见性与基础交互。
需本地服务已启动（默认 http://localhost:8090）。
"""
from playwright.sync_api import sync_playwright

import os

BASE = os.environ.get("ILINK_BASE_URL", "http://localhost:8090")

PAGES = [
    ("/gallery.html", "#keyword", ".il-search-field"),
    ("/team-market.html", "#keyword", ".il-search-field"),
    ("/teacher-wall.html", "#keyword", ".il-search-field"),
    ("/community.html", "#keywordInput", ".il-search-field"),
    ("/competitions.html", "#compSearchInput", ".il-search-field"),
]

# 管理后台需登录，单独用静态模板契约 + 可选登录探测
ADMIN_PAGE = ("/admin.html", "#userSearchInput", ".il-search-field")


def test_search_fields_render():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        failures = []
        for path, input_sel, wrap_sel in PAGES:
            url = BASE + path
            try:
                page.goto(url, wait_until="networkidle", timeout=30000)
                wrap = page.locator(wrap_sel).first
                wrap.wait_for(state="visible", timeout=10000)
                inp = page.locator(input_sel)
                inp.wait_for(state="visible", timeout=5000)
                assert wrap.locator(".il-search-field__icon").count() >= 1
                inp.fill("测试")
                assert inp.input_value() == "测试"
            except Exception as e:
                failures.append(f"{path}: {e}")

        admin_path, admin_inp, admin_wrap = ADMIN_PAGE
        try:
            page.goto(BASE + admin_path, wait_until="networkidle", timeout=30000)
            if "login" not in page.url.lower():
                page.locator(admin_wrap).first.wait_for(state="visible", timeout=10000)
                page.locator(admin_inp).fill("admin-test")
        except Exception as e:
            failures.append(f"{admin_path}: {e}")

        browser.close()
        if failures:
            raise AssertionError("搜索框检查失败:\n" + "\n".join(failures))


if __name__ == "__main__":
    test_search_fields_render()
    print("OK: all search fields passed")
