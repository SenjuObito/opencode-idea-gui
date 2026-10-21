package com.opencodebuddy.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JsUtilsTest {

    @Test
    public void unescapeJsIsTheInverseOfEscapeJs() {
        String text = "quote \" apostrophe ' backtick ` path C:\\tmp nul \0 "
                + "nel \u0085 ls \u2028 ps \u2029 </script> real\nline\ttab";
        assertEquals(text, JsUtils.unescapeJs(JsUtils.escapeJs(text)));

        String json = "{\"text\":\"a\\nb\"}";
        assertEquals(json, JsUtils.unescapeJs(JsUtils.escapeJs(json)));
    }
}
