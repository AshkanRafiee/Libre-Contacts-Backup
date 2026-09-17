package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URI;

/** The About screen's app website link must stay a valid HTTPS URL on the app host. */
@RunWith(AndroidJUnit4.class)
public class AboutLinksTest {

    @Test public void website_is_https_on_the_app_host() throws Exception {
        URI uri = new URI(AboutActivity.APP_WEBSITE);
        assertEquals("https", uri.getScheme());
        assertEquals("librecontactsbackup.ashkanrafiee.com", uri.getHost());
    }
}
