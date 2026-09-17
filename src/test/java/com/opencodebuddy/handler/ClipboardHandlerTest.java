package com.opencodebuddy.handler;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.opencodebuddy.handler.core.HandlerContext;
import org.jetbrains.annotations.NotNull;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClipboardHandlerTest {

    @BeforeClass
    public static void setUpApplication() {
        if (ApplicationManager.getApplication() == null) {
            ApplicationManager.setApplication(invokeLaterInlineApplication(), () -> {});
        }
    }

    @Test
    public void getSupportedTypesIncludesReadWriteAndCopy() {
        HandlerContext context = new HandlerContext(null, null, null, new MockJsCallback());
        ClipboardHandler handler = new ClipboardHandler(context);

        Set<String> supported = new HashSet<>(Arrays.asList(handler.getSupportedTypes()));
        assertTrue(supported.contains("read_clipboard"));
        assertTrue(supported.contains("write_clipboard"));
        assertTrue(supported.contains("copy_to_clipboard"));
    }

    @Test
    public void handlesSupportedTypesAndEmitsCallback() {
        MockJsCallback jsCallback = new MockJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, jsCallback);
        ClipboardHandler handler = new ClipboardHandler(context);

        assertTrue(handler.handle("write_clipboard", "https://opencode.ai/share/123"));
        assertTrue(jsCallback.calls.contains("window.onCopyToClipboardResult:true"));

        jsCallback.calls.clear();
        assertTrue(handler.handle("copy_to_clipboard", "https://opencode.ai/share/456"));
        assertTrue(jsCallback.calls.contains("window.onCopyToClipboardResult:true"));

        assertFalse(handler.handle("unknown_type", "test"));
    }

    @Test
    public void handlesHeadlessExceptionGracefullyWithFallback() {
        MockJsCallback jsCallback = new MockJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, jsCallback);
        ClipboardHandler handler = new ClipboardHandler(context, () -> {
            throw new java.awt.HeadlessException("Headless mode in CI runner");
        });

        assertTrue(handler.handle("write_clipboard", "headless-test-text"));
        assertTrue(jsCallback.calls.contains("window.onCopyToClipboardResult:true"));

        jsCallback.calls.clear();
        assertTrue(handler.handle("read_clipboard", ""));
        assertTrue(jsCallback.calls.contains("window.onClipboardRead:headless-test-text"));
    }

    @Test
    public void rejectsOversizedContent() {
        MockJsCallback jsCallback = new MockJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, jsCallback);
        ClipboardHandler handler = new ClipboardHandler(context);

        String hugeContent = "x".repeat(11 * 1024 * 1024);
        assertTrue(handler.handle("write_clipboard", hugeContent));
        assertTrue(jsCallback.calls.contains("window.onCopyToClipboardResult:false"));
    }

    private static @NotNull Application invokeLaterInlineApplication() {
        return (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] { Application.class },
                (proxy, method, args) -> {
                    if ("invokeLater".equals(method.getName()) && args != null && args.length >= 1) {
                        ((Runnable) args[0]).run();
                        return null;
                    }
                    if ("getAnyModalityState".equals(method.getName()) ||
                            "getDefaultModalityState".equals(method.getName()) ||
                            "getCurrentModalityState".equals(method.getName()) ||
                            "getNoneModalityState".equals(method.getName())) {
                        return ModalityState.NON_MODAL;
                    }
                    if ("isDispatchThread".equals(method.getName())) {
                        return Boolean.TRUE;
                    }
                    return null;
                });
    }

    private static final class MockJsCallback implements HandlerContext.JsCallback {
        final List<String> calls = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            calls.add(functionName + ":" + String.join(",", args));
        }

        @Override
        public String escapeJs(String str) {
            return str != null ? str : "";
        }
    }
}
