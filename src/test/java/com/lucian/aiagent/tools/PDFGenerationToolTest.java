package com.lucian.aiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "AI 助手原创项目.pdf";
        String content = "AI 助手原创项目 https://www.example.com";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}