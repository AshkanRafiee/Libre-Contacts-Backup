package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URI;

/** The About screen's website and issues links must stay valid HTTPS URLs. */
@RunWith(AndroidJUnit4.class)
public class AboutLinksTest {

    @Test public void website_is_https_on_the_app_host() throws Exception {
        URI uri = new URI(AboutActivity.APP_WEBSITE);
        assertEquals("https", uri.getScheme());
        assertEquals("librecontactsbackup.ashkanrafiee.com", uri.getHost());
    }

    @Test public void issues_link_points_at_the_libre_repo() throws Exception {
        URI uri = new URI(AboutActivity.ISSUES_URL);
        assertEquals("https", uri.getScheme());
        assertEquals("github.com", uri.getHost());
        assertEquals("/AshkanRafiee/Libre-Contacts-Backup/issues", uri.getPath());
    }

    @Test public void donation_link_points_at_the_website_donate_section() throws Exception {
        URI uri = new URI(AboutActivity.DONATION_URL);
        assertEquals("https", uri.getScheme());
        assertEquals("librecontactsbackup.ashkanrafiee.com", uri.getHost());
        assertEquals("donate", uri.getFragment());
    }
}
