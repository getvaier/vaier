package net.vaier.application.service;

import net.vaier.application.GetOfflinePageUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.GatewayError;
import org.springframework.stereotype.Service;

@Service
public class OfflinePageService implements GetOfflinePageUseCase {

    private static final String CONTENT_TYPE = "text/html; charset=utf-8";

    private final ConfigResolver configResolver;

    public OfflinePageService(ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    @Override
    public OfflinePage render(int status, String serviceHost) {
        // The title/message for a given status is a business decision — it lives in the domain.
        GatewayError error = GatewayError.forStatus(status);
        String dashboardUrl = "https://vaier." + nullToEmpty(configResolver.getDomain()) + "/";
        String html = renderHtml(error, serviceHost, dashboardUrl);
        return new OfflinePage(error.status(), CONTENT_TYPE, html);
    }

    private String renderHtml(GatewayError error, String serviceHost, String dashboardUrl) {
        String safeHost = serviceHost == null || serviceHost.isBlank() ? null : escape(serviceHost);
        String hostLine = safeHost == null
            ? ""
            : "<p class=\"host\">" + safeHost + "</p>";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%TITLE% &middot; Vaier</title>
            <link rel="icon" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 16 16%22 fill=%22none%22 stroke=%22%234fc3f7%22 stroke-width=%221.5%22 stroke-linecap=%22round%22><circle cx=%228%22 cy=%228%22 r=%222.2%22 fill=%22%234fc3f7%22 stroke=%22none%22/><line x1=%222.5%22 y1=%223.5%22 x2=%226%22 y2=%226.5%22/><line x1=%2213.5%22 y1=%223.5%22 x2=%2210%22 y2=%226.5%22/><line x1=%222.5%22 y1=%2212.5%22 x2=%226%22 y2=%229.5%22/><line x1=%2213.5%22 y1=%2212.5%22 x2=%2210%22 y2=%229.5%22/><circle cx=%222.5%22 cy=%223.5%22 r=%221.5%22 fill=%22%234fc3f7%22 stroke=%22none%22/><circle cx=%2213.5%22 cy=%223.5%22 r=%221.5%22 fill=%22%234fc3f7%22 stroke=%22none%22/><circle cx=%222.5%22 cy=%2212.5%22 r=%221.5%22 fill=%22%234fc3f7%22 stroke=%22none%22/><circle cx=%2213.5%22 cy=%2212.5%22 r=%221.5%22 fill=%22%234fc3f7%22 stroke=%22none%22/></svg>">
            <style>
            :root{--bg:#1e1e1e;--bg-card:#2d2d30;--border:#3c3c3c;--text:#d4d4d4;--text-muted:#858585;--accent:#4fc3f7;--accent-hover:#81d4fa;}
            *{margin:0;padding:0;box-sizing:border-box;}
            html,body{height:100%;}
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text);font-size:14px;line-height:1.6;display:flex;align-items:center;justify-content:center;padding:1.5rem;}
            .card{background:var(--bg-card);border:1px solid var(--border);border-radius:10px;padding:2.5rem;max-width:520px;width:100%;text-align:center;}
            .brand{display:flex;align-items:center;justify-content:center;gap:8px;color:var(--accent);font-weight:600;margin-bottom:1.5rem;}
            .brand svg{display:block;}
            .code{font-size:2.6rem;font-weight:700;color:var(--accent);letter-spacing:0.05em;}
            h1{font-size:1.25rem;margin:0.5rem 0 0.75rem;color:var(--text);}
            p{color:var(--text-muted);}
            .host{margin-top:0.75rem;font-family:'SF Mono','Cascadia Code',Consolas,monospace;color:var(--text);background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:0.4rem 0.7rem;display:inline-block;word-break:break-all;}
            .actions{margin-top:1.75rem;display:flex;gap:0.75rem;justify-content:center;flex-wrap:wrap;}
            a.btn,button.btn{font:inherit;cursor:pointer;text-decoration:none;border-radius:6px;padding:0.55rem 1.1rem;border:1px solid var(--border);background:transparent;color:var(--text);transition:border-color .15s,color .15s;}
            a.btn.primary,button.btn.primary{background:var(--accent);border-color:var(--accent);color:#04212e;font-weight:600;}
            a.btn:hover{border-color:var(--accent);color:var(--accent-hover);}
            a.btn.primary:hover{background:var(--accent-hover);border-color:var(--accent-hover);color:#04212e;}
            </style>
            </head>
            <body>
            <main class="card">
            <div class="brand">
            <svg width="18" height="18" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><circle cx="8" cy="8" r="2.2" fill="currentColor" stroke="none"/><line x1="2.5" y1="3.5" x2="6" y2="6.5"/><line x1="13.5" y1="3.5" x2="10" y2="6.5"/><line x1="2.5" y1="12.5" x2="6" y2="9.5"/><line x1="13.5" y1="12.5" x2="10" y2="9.5"/><circle cx="2.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="2.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/></svg>
            <span>Vaier</span>
            </div>
            <div class="code">%CODE%</div>
            <h1>%TITLE%</h1>
            <p>%MESSAGE%</p>
            %HOST%
            <div class="actions">
            <button class="btn primary" onclick="location.reload()">Retry</button>
            <a class="btn" href="%DASHBOARD%">Back to dashboard</a>
            </div>
            </main>
            </body>
            </html>
            """
            .replace("%CODE%", String.valueOf(error.status()))
            .replace("%TITLE%", escape(error.title()))
            .replace("%MESSAGE%", escape(error.message()))
            .replace("%HOST%", hostLine)
            .replace("%DASHBOARD%", escape(dashboardUrl));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
