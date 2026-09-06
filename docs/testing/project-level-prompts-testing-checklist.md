# Project-Level Prompts Testing Checklist

## Overview

This document provides a comprehensive manual testing checklist for the project-level prompts feature implementation.

**Feature**: Dual-scope prompt management (Global + Project)
**Test Date**: _To be filled during testing_
**Tester**: _To be filled during testing_

---

## Pre-Testing Setup

- [ ] Build the project: `./gradlew buildPlugin`
- [ ] Install the plugin in IntelliJ IDEA
- [ ] Open a test project in IntelliJ IDEA
- [ ] Ensure no existing `.codemoss/prompt.json` in home directory or project
- [ ] Clear browser cache for webview

---

## 1. Global Prompts Management

### 1.1 Create Global Prompt
- [ ] Open Settings (Tools → Codemoss → Settings)
- [ ] Navigate to "Prompt Library" section
- [ ] Click "Create" button in "Global Prompts" section
- [ ] Enter prompt name: "Test Global Prompt"
- [ ] Enter prompt content: "This is a test global prompt"
- [ ] Click "Save"
- [ ] **Expected**: Prompt appears in global prompts list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: File `~/.codemoss/prompt.json` exists and contains the prompt

### 1.2 Edit Global Prompt
- [ ] Click the three-dot menu (⋮) on a global prompt
- [ ] Select "Edit"
- [ ] Change prompt name to "Updated Global Prompt"
- [ ] Change content to "Updated content"
- [ ] Click "Save"
- [ ] **Expected**: Prompt name and content updated in the list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: Changes persisted in `~/.codemoss/prompt.json`

### 1.3 Delete Global Prompt
- [ ] Click the three-dot menu on a global prompt
- [ ] Select "Delete"
- [ ] Confirm deletion in the dialog
- [ ] **Expected**: Prompt removed from list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: Prompt removed from `~/.codemoss/prompt.json`

### 1.4 Export Global Prompts
- [ ] Create 2-3 global prompts
- [ ] Click "Export" button in global section
- [ ] Select prompts to export
- [ ] Click "Export"
- [ ] Choose export location
- [ ] **Expected**: JSON file created with selected prompts
- [ ] **Verify**: Exported file is valid JSON
- [ ] **Verify**: Exported prompts match selection

### 1.5 Import Global Prompts
- [ ] Click "Import" button in global section
- [ ] Select a valid prompt JSON file
- [ ] Review import preview dialog
- [ ] Select conflict resolution strategy (SKIP/OVERWRITE/DUPLICATE)
- [ ] Click "Import"
- [ ] **Expected**: Prompts imported according to strategy
- [ ] **Expected**: Import summary message displayed
- [ ] **Verify**: Imported prompts appear in global list
- [ ] **Verify**: Conflicts handled correctly

---

## 2. Project Prompts Management

### 2.1 Create Project Prompt
- [ ] Open Settings
- [ ] Navigate to "Prompt Library" section
- [ ] Verify "Project Prompts - {ProjectName}" section appears
- [ ] Click "Create" button in project section
- [ ] Enter prompt name: "Test Project Prompt"
- [ ] Enter prompt content: "This is a test project prompt"
- [ ] Click "Save"
- [ ] **Expected**: Prompt appears in project prompts list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: File `<project>/.codemoss/prompt.json` exists and contains the prompt

### 2.2 Edit Project Prompt
- [ ] Click the three-dot menu on a project prompt
- [ ] Select "Edit"
- [ ] Change prompt name to "Updated Project Prompt"
- [ ] Change content to "Updated project content"
- [ ] Click "Save"
- [ ] **Expected**: Prompt updated in project list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: Changes persisted in `<project>/.codemoss/prompt.json`

### 2.3 Delete Project Prompt
- [ ] Click the three-dot menu on a project prompt
- [ ] Select "Delete"
- [ ] Confirm deletion
- [ ] **Expected**: Prompt removed from project list
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: Prompt removed from `<project>/.codemoss/prompt.json`

### 2.4 Export Project Prompts
- [ ] Create 2-3 project prompts
- [ ] Click "Export" button in project section
- [ ] Select prompts to export
- [ ] Click "Export"
- [ ] Choose export location
- [ ] **Expected**: JSON file created with selected prompts
- [ ] **Verify**: Exported file is valid JSON
- [ ] **Verify**: Exported prompts match selection

### 2.5 Import Project Prompts
- [ ] Click "Import" button in project section
- [ ] Select a valid prompt JSON file
- [ ] Review import preview dialog
- [ ] Select conflict resolution strategy
- [ ] Click "Import"
- [ ] **Expected**: Prompts imported to project scope
- [ ] **Expected**: Import summary displayed
- [ ] **Verify**: Imported prompts appear in project list

### 2.6 Copy Project Prompt to Global
- [ ] Create a project prompt
- [ ] Click the three-dot menu on the prompt
- [ ] Select "Copy to Global"
- [ ] **Expected**: Prompt copied to global scope
- [ ] **Expected**: Success message displayed
- [ ] **Verify**: Prompt appears in both project and global lists
- [ ] **Verify**: Both copies have unique IDs

---

## 3. Scope Isolation

### 3.1 Global and Project Independence
- [ ] Create a prompt named "Test" in global scope
- [ ] Create a prompt named "Test" in project scope
- [ ] **Expected**: Both prompts exist independently
- [ ] Edit global "Test" prompt
- [ ] **Expected**: Project "Test" prompt remains unchanged
- [ ] Delete global "Test" prompt
- [ ] **Expected**: Project "Test" prompt still exists

### 3.2 ID Uniqueness
- [ ] Create multiple prompts in global scope
- [ ] Create multiple prompts in project scope
- [ ] **Verify**: All prompt IDs are unique within each scope
- [ ] **Verify**: IDs don't conflict between scopes

---

## 4. Multi-Project Support

### 4.1 Switch Between Projects
- [ ] Open Project A
- [ ] Create project prompts in Project A
- [ ] Note project prompt names
- [ ] Close Project A
- [ ] Open Project B
- [ ] Open Settings
- [ ] **Expected**: Project section shows "Project Prompts - Project B"
- [ ] **Expected**: Project A's prompts NOT visible
- [ ] Create different project prompts in Project B
- [ ] Close Project B
- [ ] Reopen Project A
- [ ] **Expected**: Project A's prompts visible again
- [ ] **Expected**: Project B's prompts NOT visible

### 4.2 No Active Project
- [ ] Close all projects
- [ ] Open the plugin without a project
- [ ] Open Settings
- [ ] **Expected**: "No Active Project" message displayed
- [ ] **Expected**: Global prompts still accessible
- [ ] **Expected**: Project section disabled or hidden

---

## 5. Chat Integration

### 5.1 Autocomplete with Global Prompts
- [ ] Create 2-3 global prompts with distinct names
- [ ] Open chat input
- [ ] Type "/" to trigger autocomplete
- [ ] **Expected**: Global prompts appear with "[全局]" label
- [ ] Select a global prompt
- [ ] **Expected**: Prompt content inserted into chat

### 5.2 Autocomplete with Project Prompts
- [ ] Create 2-3 project prompts with distinct names
- [ ] Open chat input
- [ ] Type "/" to trigger autocomplete
- [ ] **Expected**: Project prompts appear with "[项目]" label
- [ ] **Expected**: Project prompts appear BEFORE global prompts
- [ ] Select a project prompt
- [ ] **Expected**: Prompt content inserted into chat

### 5.3 Autocomplete with Both Scopes
- [ ] Ensure both global and project prompts exist
- [ ] Open chat input
- [ ] Type "/" to trigger autocomplete
- [ ] **Expected**: Both scopes visible
- [ ] **Expected**: Project prompts listed first
- [ ] **Expected**: Clear scope labels distinguish prompts
- [ ] **Expected**: Prompts with same name in different scopes both visible

---

## 6. Error Handling

### 6.1 Invalid JSON Import
- [ ] Click "Import" in either section
- [ ] Select an invalid JSON file
- [ ] **Expected**: Error message displayed
- [ ] **Expected**: No prompts imported
- [ ] **Expected**: Existing prompts unaffected

### 6.2 Permission Errors
- [ ] Make `~/.codemoss` directory read-only
- [ ] Try to create a global prompt
- [ ] **Expected**: Error message displayed
- [ ] Restore permissions

### 6.3 Empty Project Path
- [ ] Create a project without a base path (if possible)
- [ ] Try to create a project prompt
- [ ] **Expected**: Error message or graceful degradation
- [ ] **Expected**: Global prompts still functional

---

## 7. UI/UX Validation

### 7.1 Layout and Styling
- [ ] **Verify**: Two distinct sections (Global and Project)
- [ ] **Verify**: Section headers clearly labeled
- [ ] **Verify**: Action buttons properly aligned
- [ ] **Verify**: Prompt cards have consistent styling
- [ ] **Verify**: Dropdown menus positioned correctly
- [ ] **Verify**: Loading spinners appear during operations

### 7.2 Internationalization
- [ ] Switch language to English (en-US)
- [ ] **Verify**: All labels translated to English
- [ ] **Verify**: "Global Prompts" and "Project Prompts" labels correct
- [ ] Switch language to Chinese (zh-CN)
- [ ] **Verify**: All labels translated to Chinese
- [ ] **Verify**: "全局提示词" and "项目提示词" labels correct

### 7.3 Responsive Behavior
- [ ] Resize settings window to minimum width
- [ ] **Verify**: UI remains usable
- [ ] **Verify**: No text overflow or layout breaks
- [ ] Expand window to maximum width
- [ ] **Verify**: UI scales appropriately

---

## 8. Edge Cases

### 8.1 Empty States
- [ ] Delete all global prompts
- [ ] **Expected**: "No prompts available" message in global section
- [ ] Delete all project prompts
- [ ] **Expected**: "No prompts available" message in project section

### 8.2 Large Number of Prompts
- [ ] Import 50+ prompts into global scope
- [ ] **Verify**: List scrollable
- [ ] **Verify**: Performance acceptable
- [ ] **Verify**: Search/filter works (if implemented)

### 8.3 Special Characters in Names
- [ ] Create prompts with special characters: `@#$%^&*()`
- [ ] **Verify**: Names saved and displayed correctly
- [ ] **Verify**: No encoding issues

### 8.4 Very Long Prompt Content
- [ ] Create a prompt with 10,000+ characters of content
- [ ] **Verify**: Content saved successfully
- [ ] **Verify**: UI truncates display appropriately
- [ ] **Verify**: Full content accessible on edit

---

## 9. Backward Compatibility

### 9.1 Existing Global Prompts
- [ ] Manually create `~/.codemoss/prompt.json` with old format prompts
- [ ] Open Settings
- [ ] **Verify**: Existing prompts load in global section
- [ ] **Verify**: Can edit and delete existing prompts
- [ ] **Verify**: New prompts saved in compatible format

### 9.2 Migration from Single Scope
- [ ] Start with only old global prompts
- [ ] Create first project prompt
- [ ] **Verify**: Global prompts unaffected
- [ ] **Verify**: Project prompts stored in correct location

---

## 10. Performance

### 10.1 Load Time
- [ ] Measure time to open Settings with 100 global prompts
- [ ] **Expected**: < 2 seconds to fully load
- [ ] Measure time to open Settings with 100 project prompts
- [ ] **Expected**: < 2 seconds to fully load

### 10.2 Operation Speed
- [ ] Measure time to create a prompt
- [ ] **Expected**: < 500ms
- [ ] Measure time to delete a prompt
- [ ] **Expected**: < 500ms
- [ ] Measure time to import 50 prompts
- [ ] **Expected**: < 3 seconds

---

## Bug Tracker

| # | Description | Severity | Steps to Reproduce | Status |
|---|-------------|----------|-------------------|--------|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |

---

## Test Summary

**Total Test Cases**: 80+

**Passed**: ___
**Failed**: ___
**Blocked**: ___
**Not Tested**: ___

**Pass Rate**: ___%

---

## Notes

_Add any additional observations, suggestions, or issues encountered during testing._

---

## Sign-off

**Tested by**: _______________
**Date**: _______________
**Approved by**: _______________
**Date**: _______________
