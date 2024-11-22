package de.intranda.goobi.plugins;

import com.lowagie.text.Document;
import com.lowagie.text.pdf.PRAcroForm;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.SimpleBookmark;
import de.sub.goobi.helper.StorageProvider;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
import de.unigoettingen.sub.commons.contentlib.servlet.controller.GetPdfAction;
import de.unigoettingen.sub.commons.contentlib.servlet.model.ContentServerConfiguration;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.JournalEntry;
import org.goobi.beans.Process;
import org.goobi.production.enums.LogType;
import org.jdom2.Element;
import ugh.dl.DocStruct;
import ugh.dl.Metadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Log4j2
public class AdmBsmeExportHelper {

    /**
     * copy files to target directory
     * 
     * @param ds
     * @throws IOException
     */
    public static void copyFolderContent(String sourcefolder, String ext, Map<String, String> fileMap, String targetFolder) throws IOException {
        for (Path pathIn : StorageProvider.getInstance().listFiles(sourcefolder)) {
            String fileIn = pathIn.getFileName().toString();
            fileIn = fileIn.substring(0, fileIn.indexOf("."));
            String fileOut = fileMap.get(fileIn);
            // Skip files that are not mapped
            if (fileOut == null) {
                continue;
            }
            Path pathOut = Paths.get(targetFolder, fileOut + "." + ext);
            // log.debug(pathIn + " ---> " + pathOut);
            StorageProvider.getInstance().copyFile(pathIn, pathOut);
        }
    }

    /**
     * get a specific metadata from given docstruct
     * 
     * @param ds
     */
    public static String getMetdata(DocStruct ds, String field) {
        // run through all metadata to find the right one
        for (Metadata md : ds.getAllMetadata()) {
            if (md.getType().getName().equals(field)) {
                return md.getValue();
            }
        }
        return "";
    }

    /**
     * extremely simple method to remove Ausgabe or Issue Information from title of an issue
     *
     * @param value
     * @return
     */
    public static String getCleanIssueLabel(String value) {
        String copy = value;
        copy = copy.replace("Ausgabe vom ", "");
        copy = copy.replace("Issue from ", "");
        return copy;
    }

    /**
     * get English part of a metadata that is separated from Arabic part
     *
     * @param value
     * @return
     */
    public static String getEnglishPartOfString(String value) {
        String copy = value.replace("–", "-");
        if (copy.contains("-")) {
            copy = StringUtils.substringAfter(copy, "-").trim();
        }
        return copy;
    }

    /**
     * get Arabic part of a metadata that is separated from English part
     *
     * @param value
     * @return
     */
    public static String getArabicPartOfString(String value) {
        String copy = value.replace("–", "-");
        if (copy.contains("-")) {
            copy = StringUtils.substringBefore(copy, "-").trim();
        }
        return copy;
    }

    /**
     * get the language from metadata
     * 
     * @param ds
     */
    public static String getLanguageFullname(DocStruct ds, String field) {
        String lang = getMetdata(ds, field);
        switch (lang) {
            case "Arabic":
                return "عربي - Arabic";
            case "ara":
                return "عربي - Arabic";
            case "English":
                return "انجليزي - English";
            case "eng":
                return "انجليزي - English";
            case "ger":
                return "German";
        }
        return lang;
    }

    /**
     * convert the date from dd-mm-yyyy to format yyyy-mm-dd and give it back
     * 
     * @param inputDate
     * @return
     */
    public static String convertDateFormatToDayMonthYear(String inputDate) {
        return convertDateFormat(inputDate, "yyyy-MM-dd", "dd-MM-yyyy");
    }

    /**
     * convert the date from yyyy-mm-dd to format dd-mm-yyyy and give it back
     * 
     * @param inputDate
     * @return
     */
    //    public static String convertDateFormatToYearMonthDay(String inputDate) {
    //        return convertDateFormat(inputDate, "dd-MM-yyyy", "yyyy-MM-dd");
    //    }

    /**
     * convert the date from one format to the other
     * 
     * @param inputDate
     * @return
     */
    private static String convertDateFormat(String inputDate, String inFormat, String outFormat) {
        SimpleDateFormat inputFormatter = new SimpleDateFormat(inFormat);
        SimpleDateFormat outputFormatter = new SimpleDateFormat(outFormat);

        try {
            Date date = inputFormatter.parse(inputDate);
            return outputFormatter.format(date);
        } catch (Exception e) {
            return inputDate;
        }
    }

    public static Element createTechnicalNotesElementFromRelevantJournalEntries(Process process) {
        Element result = new Element("Technical_Notes");

        List<JournalEntry> journal = Optional.ofNullable(process.getJournal())
                .map(j -> j.stream()
                        .filter(e -> e.getType() == LogType.IMPORTANT_USER)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        journal.forEach(e ->
                result.addContent(new Element("Entry").setAttribute("date", e.getFormattedCreationDate())
                        .setAttribute("type", e.getType().getTitle())
                        .setText(e.getFormattedContent()))
        );

        return result;
    }

    public static void generatePDF(PdfIssue pdfi) throws ContentLibException, IOException {
        Map<String, String> map = pdfi.getAsMap();
        FileOutputStream fout;
        fout = new FileOutputStream(pdfi.getName());
        new GetPdfAction().writePdf(map, ContentServerConfiguration.getInstance(), fout);
        fout.close();
    }

    public static void gluePDF(List<File> inputFiles, File outputFile) throws IOException {
        if (inputFiles.isEmpty()) {
            log.warn("No input files to glue together");
            return;
        }

        int pageOffset = 0;
        List<Map<String, Object>> master = new ArrayList<>();
        Document document = null;
        PdfCopy writer = null;
        for (File file : inputFiles) {
            // we create a reader for a certain document
            PdfReader reader = new PdfReader(file.getAbsolutePath());
            reader.consolidateNamedDestinations();

            List<Map<String, Object>> bookmarks = SimpleBookmark.getBookmarkList(reader);
            if (bookmarks != null) {
                if (pageOffset != 0) {
                    SimpleBookmark.shiftPageNumbersInRange(bookmarks, pageOffset, null);
                }
                master.addAll(bookmarks);
            }
            pageOffset += 1;

            if (document == null) {
                // step 1: creation of a document-object
                document = new Document(reader.getPageSizeWithRotation(1));
                // step 2: we create a writer that listens to the document
                writer = new PdfCopy(document, new FileOutputStream(outputFile));
                // step 3: we open the document
                document.open();
            }
            // step 4: we add only the first page
            PdfImportedPage firstPage = writer.getImportedPage(reader, 1);
            writer.addPage(firstPage);

            PRAcroForm form = reader.getAcroForm();
            if (form != null) {
                writer.copyAcroForm(reader);
            }
        }

        if (!master.isEmpty()) {
            writer.setOutlines(master);
        }
        // step 5: we close the document
        document.close();
    }
}
