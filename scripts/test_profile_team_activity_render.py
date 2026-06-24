from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
PROFILE_JS = ROOT / "src" / "main" / "resources" / "static" / "js" / "profile.js"


def main():
    html = """
    <!doctype html>
    <html lang="zh-CN">
    <body data-profile-page="overview">
      <div id="content-teams">
        <div class="il-empty-state">还没有组队</div>
      </div>
      <div id="content-applications"></div>
      <script>
        window.escapeHtml = function(value) {
          return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
        };
        window.formatTime = function(value) { return value || ''; };
        window.showMessage = function() {};
        window.request = async function() { return {}; };
      </script>
    </body>
    </html>
    """
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.set_content(html, wait_until="domcontentloaded")
        page.add_script_tag(path=str(PROFILE_JS))
        page.evaluate(
            """
            () => renderOverview({}, [], {
              published: [{
                id: 101,
                title: '我正在发布的组队需求',
                status: 'OPEN',
                createdAt: '2026-06-23T12:00:00',
                deadline: '2026-07-01',
                requiredMemberCount: 3,
                applicationCount: 1,
                approvedMemberCount: 0,
                canEdit: true,
                canMoveToTeaming: true,
                canClose: true,
                canDelete: false
              }],
              applications: []
            })
            """
        )
        content = page.locator("#content-teams").inner_text()
        assert "我正在发布的组队需求" in content, content
        assert "还没有组队" not in content, content
        browser.close()


if __name__ == "__main__":
    main()
