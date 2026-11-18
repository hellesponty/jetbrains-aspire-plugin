package me.hellesponty.aspire.cli

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DashboardLinks {
    /**
     * A link to a resource page of the dashboard that also signs the browser in.
     *
     * `aspire ps` reports the AppHost's dashboard as `http://host:port/login?t=<token>`, while resource
     * pages (`http://host:port/?resource=x`) carry no token and bounce to a login form in a fresh browser.
     * The dashboard's `/login` accepts the token together with `returnUrl` and redirects there after
     * setting its auth cookie.
     *
     * Falls back to [resourceUrl] unchanged when there is no token to add.
     */
    fun withLogin(resourceUrl: String, appHostDashboardUrl: String?): String {
        if (appHostDashboardUrl == null) return resourceUrl
        val login = runCatching { URI(appHostDashboardUrl) }.getOrNull() ?: return resourceUrl
        val target = runCatching { URI(resourceUrl) }.getOrNull() ?: return resourceUrl
        if (login.rawPath?.endsWith("/login") != true) return resourceUrl
        // Only add the token for the dashboard it belongs to.
        if (login.scheme != target.scheme || login.rawAuthority != target.rawAuthority) return resourceUrl
        val token = login.rawQuery?.split('&')?.firstOrNull { it.startsWith("t=") && it.length > 2 } ?: return resourceUrl
        val returnUrl = (target.rawPath?.ifEmpty { "/" } ?: "/") + (target.rawQuery?.let { "?$it" } ?: "")
        val encoded = URLEncoder.encode(returnUrl, StandardCharsets.UTF_8)
        return "${login.scheme}://${login.rawAuthority}${login.rawPath}?$token&returnUrl=$encoded"
    }
}
