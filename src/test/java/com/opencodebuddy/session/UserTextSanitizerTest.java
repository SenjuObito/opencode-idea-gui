package com.opencodebuddy.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserTextSanitizerTest {

    @Test
    public void stripsIdeContextSectionAtEnd() {
        String text = "帮我重构这段代码\n\n"
                + "## IDE Context\n\n"
                + "Active file: `src/App.tsx#L10-20`\n\n"
                + "The user has selected the referenced lines in this file.";
        assertEquals("帮我重构这段代码", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void stripsReferencedFilesAndAgentRoleTogether() {
        String text = "看看这些文件\n\n"
                + "## Referenced Files\n\n"
                + "The following files were referenced by the user:\n\n"
                + "- `/abs/gradlew`\n\n"
                + "Read them with your file tools as needed.\n\n"
                + "## Agent Role and Instructions\n\n"
                + "You are a senior reviewer.";
        assertEquals("看看这些文件", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void keepsUserMarkdownHeadingsBetweenSections() {
        String text = "intro\n\n"
                + "## User's Current IDE Context\n\n"
                + "The user is viewing this file in their IDE: `/a/b.ts`\n\n"
                + "## My Own Notes\n\n"
                + "user content stays";
        assertEquals("intro\n\n## My Own Notes\n\nuser content stays", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void keepsUserTypedMentions() {
        String text = "帮我看看 @gradlew 这个文件\n\n"
                + "## IDE Context\n\n"
                + "Active file: `/abs/gradlew`\n";
        assertEquals("帮我看看 @gradlew 这个文件", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void allKnownSectionsAreStripped() {
        String text = "t\n\n"
                + "## Workspace Context\n\nmulti-project\n\n"
                + "## Project Modules\n\n- mod-a\n\n"
                + "## Active Terminal Session\n\n- Terminal: local\n\n"
                + "## Referenced Files\n\n- f\n\n"
                + "## IDE Context\n\nActive file: `x`\n\n"
                + "## User's Current IDE Context\n\nviewing x\n\n"
                + "## Agent Role and Instructions\n\nrole";
        assertEquals("t", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void sanitizeIsIdempotent() {
        String text = "用户文本\n\n## IDE Context\n\nActive file: `x`\n\n普通内容";
        String once = UserTextSanitizer.sanitize(text);
        assertEquals(once, UserTextSanitizer.sanitize(once));
    }

    @Test
    public void cleanTextPassesThroughUnchanged() {
        String text = "普通消息，无注入段。## IDE Context 不是行首标题\n\n## 自定义标题\n\n内容";
        assertEquals(text, UserTextSanitizer.sanitize(text));
    }

    @Test
    public void nullAndEmptyInputsReturnEmpty() {
        assertEquals("", UserTextSanitizer.sanitize(null));
        assertEquals("", UserTextSanitizer.sanitize(""));
    }

    @Test
    public void sectionOnlyTextSanitizesToEmpty() {
        String text = "\n\n## IDE Context\n\nActive file: `x`";
        assertEquals("", UserTextSanitizer.sanitize(text));
    }

    @Test
    public void needsSanitizeDetectsSections() {
        assertTrue(UserTextSanitizer.needsSanitize("a\n\n## Referenced Files\n\n- x"));
        assertFalse(UserTextSanitizer.needsSanitize("a\n\n## My Heading\n\nb"));
        assertFalse(UserTextSanitizer.needsSanitize(""));
    }
}
