package com.plink.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Page and extracted-line numbers are retained before artwork interpretation. */
record PdfTextDocument(List<Page> pages) {
    record Line(int number, String text) {}
    record Page(int number, String text, List<Line> lines) {}

    static PdfTextDocument extract(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setPageEnd("");
        List<Page> pages = new ArrayList<>();
        for (int number = 1; number <= pdf.getNumberOfPages(); number++) {
            stripper.setStartPage(number);
            stripper.setEndPage(number);
            pages.add(page(number, stripper.getText(pdf).replace("\r", "")));
        }
        return new PdfTextDocument(List.copyOf(pages));
    }

    static PdfTextDocument fromText(String text) {
        List<Page> pages = new ArrayList<>();
        for (String value : text.split("\f", -1)) pages.add(page(pages.size() + 1, value));
        return new PdfTextDocument(List.copyOf(pages));
    }

    private static Page page(int number, String text) {
        List<Line> lines = new ArrayList<>();
        for (String value : text.split("\n", -1)) lines.add(new Line(lines.size() + 1, value));
        return new Page(number, text, List.copyOf(lines));
    }

    String text() {
        return String.join("\n\f\n", pages.stream().map(Page::text).toList());
    }
}
