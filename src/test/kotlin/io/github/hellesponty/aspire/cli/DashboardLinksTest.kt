package io.github.hellesponty.aspire.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLinksTest {
    private val login = "http://localhost:18888/login?t=7b7d9acf22f5f12623e5de4808bfa54c"

    @Test
    fun `adds token and return url for a resource page`() {
        assertEquals(
            "http://localhost:18888/login?t=7b7d9acf22f5f12623e5de4808bfa54c&returnUrl=%2F%3Fresource%3Dserver-ensgrawe",
            DashboardLinks.withLogin("http://localhost:18888/?resource=server-ensgrawe", login),
        )
    }

    @Test
    fun `keeps link when dashboard has no token`() {
        val url = "http://localhost:18888/?resource=server"
        assertEquals(url, DashboardLinks.withLogin(url, "http://localhost:18888/"))
        assertEquals(url, DashboardLinks.withLogin(url, null))
    }

    @Test
    fun `never sends the token to another host`() {
        val url = "http://localhost:19999/?resource=server"
        assertEquals(url, DashboardLinks.withLogin(url, login))
    }

    @Test
    fun `resource url without path returns to root`() {
        assertEquals(
            "http://localhost:18888/login?t=abc&returnUrl=%2F",
            DashboardLinks.withLogin("http://localhost:18888", "http://localhost:18888/login?t=abc"),
        )
    }
}
