package com.opencodebuddy.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for HTML bootstrap snippets that must run before the frontend bundle.
 */
public class HtmlLoaderTest {

    /** Verifies that runtime page state starts unavailable and does not embed a generation. */
    @Test
    public void injectsPendingPageContextBeforeFrontendBundle() {
        HtmlLoader loader = new HtmlLoader(HtmlLoaderTest.class);
        String html = "<html><head><script src=\"main.js\"></script></head><body></body></html>";

        String injected = loader.injectPageContextBootstrap(html);

        int generationIndex = injected.indexOf("window.__CCG_PAGE_GENERATION__ = undefined");
        int bundleIndex = injected.indexOf("main.js");
        assertTrue(generationIndex > 0);
        assertTrue(generationIndex < bundleIndex);
        assertTrue(injected.contains("window.__CCGUI_PAGE_CONTEXT_READY__ = false"));
    }

    /** Root-tag styling must land inside the real start tag, before its '>'. */
    @Test
    public void addsStyleAttributeInsideRootTag() {
        String styled = HtmlLoader.addStyleToRootTag(
                "<!DOCTYPE html><html lang=\"zh-CN\"><head></head><body class=\"x\"></body></html>",
                "html",
                " style=\"background-color:#1e1e1e;\"");

        assertTrue(styled.contains("<html lang=\"zh-CN\" style=\"background-color:#1e1e1e;\">"));
    }

    /**
     * The bundle is one giant line. A {@code <body} substring living inside a JS
     * string literal must never be rewritten — that was the failure mode of the
     * previous whole-document regex.
     */
    @Test
    public void doesNotTouchBodySubstringInsideJavaScript() {
        StringBuilder html = new StringBuilder();
        html.append("<html><head></head><body></body></html>\n<script>var tpl = '<body id=\"wrong\">';</script>");
        // Push the literal far past any plausible document prologue.
        while (html.length() < 70 * 1024) {
            html.append('x');
        }

        String styled = HtmlLoader.addStyleToRootTag(html.toString(), "body", " style=\"color:red;\"");

        assertTrue(styled.contains("<body style=\"color:red;\"></body>"));
        assertFalse(styled.contains("<body id=\"wrong\" style="));
    }

    /** `<htmlx>` shares a prefix with `<html>` and must not be mistaken for it. */
    @Test
    public void ignoresTagsWithLongerNames() {
        String styled = HtmlLoader.addStyleToRootTag(
                "<htmlx><html><head></head><body></body></html>",
                "html",
                " style=\"color:red;\"");
        assertEquals(
                "<htmlx><html style=\"color:red;\"><head></head><body></body></html>",
                styled);
    }

    /** A self-closing root tag cannot carry a background style. */
    @Test
    public void skipsSelfClosingTag() {
        String unchanged = HtmlLoader.addStyleToRootTag("<body/>", "body", " style=\"color:red;\"");
        assertEquals("<body/>", unchanged);
    }
}
