# Uploaded catalogue fixtures

`test_file_1.pdf` is the uploaded ThisWeekendRoom catalogue (78 pages, 34 artwork captions, 5 artists).
`test_file_2.pdf` is the uploaded ARARIO Kiaf catalogue (62 pages, 34 artwork captions, 11 artists).

These are reduced copies of the supplied PDFs: image XObjects and document metadata were removed with PDFBox. Page content, fonts, text order, blank pages, and page boundaries are retained. Extraction with the previous production PDFTextStripper settings was compared with each original and is identical. The original uploads remain in the project root and are ignored by Git. Reduced files total approximately 1.1 MB instead of 33 MB.

Expected title lists and artist sections in `PdfCatalogueRegressionTest` were checked against the extracted source pages, independently of parser output. Tests run the actual PDF bytes through `ContentImportService`, and separately verify page and line extraction. They cover omitted caption artists, multiline media, punctuation in titles, currency suffixes, On Hold, duplicate titles, variable dimensions, frames, and editions.

Images are intentionally absent: these fixtures validate text extraction and interpretation, not artwork image extraction. Extracted line numbers are PDFBox output lines; they are not visual text coordinates or table columns.

Run:

```bash
./mvnw -q -Dtest=PdfCatalogueRegressionTest,PdfArtworkParserTest,ContentImportServiceTest test
```
