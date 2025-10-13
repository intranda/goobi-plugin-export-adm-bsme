package de.intranda.goobi.plugins.exporters;

import static de.intranda.goobi.plugins.AdmBsmeExportHelper.createTechnicalNotesElementFromRelevantJournalEntries;
import static de.intranda.goobi.plugins.AdmBsmeExportHelper.gluePDF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.intranda.goobi.plugins.AdmBsmeExportHelper;
import de.intranda.goobi.plugins.PdfIssue;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageInterpreter;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class NewspaperExporter {

    private XMLConfiguration config;
    private Process process;
    private Prefs prefs;
    private DigitalDocument dd;
    private String viewerUrl;
    private String targetFolder;
    private String pdfCopyFolder;

    // keep a list of all image files as they need to be renamed
    private Map<String, String> fileMap;
    private List<PdfIssue> pdfIssues;
    private int fileCounter;
    private VariableReplacer vr;

    @Getter
    private List<String> problems;

    /**
     * Constructor
     * 
     * @param config
     * @param process
     * @param prefs
     * @param dd
     */
    public NewspaperExporter(XMLConfiguration config, Process process, Prefs prefs, DigitalDocument dd) {
        this.config = config;
        config.setExpressionEngine(new XPathExpressionEngine());
        this.process = process;
        this.prefs = prefs;
        this.dd = dd;
        viewerUrl = config.getString("viewerUrl", "https://viewer.goobi.io");
        targetFolder = config.getString("targetDirectoryNewspapers", "/opt/digiverso/goobi/output/");
        pdfCopyFolder = config.getString("pdfCopyNewspapers");
        pdfIssues = new ArrayList<>();
    }

    /**
     * Do the actual export for a newspaper volume
     * 
     * @param process
     * @param destination
     * @return
     */
    public boolean startExport() {
        vr = new VariableReplacer(dd, prefs, process, null);
        problems = new ArrayList<>();
        fileMap = new HashMap<>();
        HashMap<String, Document> simpleXmlMap = new HashMap<>();
        fileCounter = 0;
        log.debug("Export directory for AdmBsmeExportPlugin: " + targetFolder);

        // in case it is an anchor file get the first child
        DocStruct anchor = dd.getLogicalDocStruct();
        DocStruct topStruct;
        if (anchor.getType().isAnchor() && anchor.getAllChildren() != null && !anchor.getAllChildren().isEmpty()) {
            topStruct = anchor.getAllChildren().get(0);
        } else {
            return false;
        }

        List<Path> pdfFiles = Collections.emptyList();
        try {
            pdfFiles = StorageProvider.getInstance().listFiles(process.getOcrPdfDirectory());
        } catch (IOException | SwapException e) {
            log.warn("Unable to find OCR PDF files", e);
        }

        final String viewerProcessPath = viewerUrl
                + "/image/"
                + AdmBsmeExportHelper.getMetdata(topStruct, "CatalogIDDigital").replace("-", "")
                + "/";

        // run through all NewspaperIssues
        for (DocStruct ds : topStruct.getAllChildrenAsFlatList()) {
            if (ds.getType().getName().equals(config.getString("/docstruct/issue"))) {
                String simpleDate = AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueDate")).replace("-", "");

                // prepare xml document
                Document doc = new Document();
                doc.setRootElement(new Element("newspaper"));

                // add volume information
                Element volume = new Element("volumeInfo");
                doc.getRootElement().addContent(volume);
                String volumeId = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/identifier"));

                String rightsToUse = vr.replace(config.getString("/rightsToUse"));
                String rightsDetails = vr.replace(config.getString("/rightsDetails"));
                String source = vr.replace(config.getString("/source"));
                String mediaType = vr.replace(config.getString("/mediaType"));
                String mediaGroup = vr.replace(config.getString("/mediaGroup"));
                String sourceOrganisation = vr.replace(config.getString("/sourceOrganisation"));
                String frequency = vr.replace(config.getString("/frequency"));
                String volumeNumber = vr.replace(config.getString("/volumeNumber"));

                volume.addContent(new Element("Rights_to_Use").setText(rightsToUse));
                volume.addContent(new Element("Right_Details").setText(rightsDetails));
                volume.addContent(new Element("Media_Source").setText(source));
                volume.addContent(new Element("Media_type").setText(mediaType));
                volume.addContent(new Element("Media_Group").setText(mediaGroup));
                volume.addContent(new Element("Publication_Name")
                        .setText(AdmBsmeExportHelper.getMetdata(anchor, config.getString("/metadata/titleLabel"))));
                volume.addContent(new Element("Language")
                        .setText(AdmBsmeExportHelper.getLanguageFullname(anchor, config.getString("/metadata/language"))));
                volume.addContent(
                        new Element("Source_Organization").setText(sourceOrganisation));
                volume.addContent(
                        new Element("Volume_Number").setText(volumeNumber));

                // volume.addContent(new Element("Publication_ID").setText(volumeId));

                // add all journal entries as technical notes
                volume.addContent(createTechnicalNotesElementFromRelevantJournalEntries(process));

                volume.addContent(new Element("Barcode").setText(volumeId));
                volume.addContent(new Element("MetadataMetsFile").setText(volumeId + ".xml").setAttribute("Format", "application/xml"));

                // add issue information
                Element issue = new Element("issueInfo");
                volume.addContent(issue);
                issue.addContent(
                        new Element("issueNumber").setText(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueNumber"))));
                issue.addContent(new Element("issueID").setText(volumeId + "-" + simpleDate + "-" + "MI"));
                issue.addContent(new Element("issueFrequency").setText(frequency));

                // get all title information
                String issueName =
                        AdmBsmeExportHelper.getCleanIssueLabel(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueName")));
                String issueTitleEng = AdmBsmeExportHelper.getEnglishPartOfString(issueName);
                String issueTitleAra = AdmBsmeExportHelper.getArabicPartOfString(issueName);

                // convert date from from yyyy-mm-dd to dd-mm-yyyy
                String date = AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueDate"));
                date = AdmBsmeExportHelper.convertDateFormatToDayMonthYear(date);

                // add an English title
                issue.addContent(new Element("issueTitleENG").setText(issueTitleEng + "-" + date));
                // add an Arabic title
                issue.addContent(new Element("issueTitleARA").setText(date + "-" + issueTitleAra));

                issue.addContent(new Element("issueName").setText(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueName"))));
                issue.addContent(new Element("issueNotes").setText(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueNotes"))));

                issue.addContent(new Element("issueDate").setText(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueDate"))));
                issue.addContent(new Element("No_of_Pages"));
                issue.addContent(new Element("Open_In_Viewer").setText(viewerProcessPath
                        + ds.getAllToReferences()
                                .getFirst()
                                .getTarget()
                                .getAllMetadata()
                                .stream()
                                .filter(m -> "physPageNumber".equals(m.getType().getName()))
                                .findFirst()
                                .map(Metadata::getValue)
                                .orElse("")));
                issue.addContent(
                        new Element("issueFile").setText(volumeId + "-" + simpleDate + "-MI" + ".pdf").setAttribute("Format", "application/pdf"));
                issue.addContent(
                        new Element("MetadataMetsFile").setText(volumeId + "-" + simpleDate + "-mets.xml").setAttribute("Format", "application/xml"));

                // add file information
                Element files = new Element("Pages");
                doc.getRootElement().addContent(files);

                PdfIssue pdfi = new PdfIssue();
                pdfi.setFolder(targetFolder);
                pdfi.setName(targetFolder + volumeId + "-" + simpleDate + "-" + "MI" + ".pdf");

                List<Reference> refs = ds.getAllToReferences("logical_physical");
                if (refs != null) {
                    for (Reference ref : refs) {
                        DocStruct page = ref.getTarget();
                        String realFileName = page.getImageName();
                        String realFileNameWithoutExtension = realFileName.substring(0, realFileName.indexOf("."));

                        // get the new file name for the image and reuse if created previously
                        String exportFileName = fileMap.get(realFileNameWithoutExtension);
                        if (exportFileName == null) {
                            String counter = String.format("%04d", ++fileCounter);
                            exportFileName = volumeId + "-" + counter;
                            fileMap.put(realFileNameWithoutExtension, exportFileName);
                        }
                        pdfi.getFiles().add(exportFileName);
                        pdfFiles.stream()
                                .map(Path::toFile)
                                .filter(f -> f.getName().substring(0, f.getName().lastIndexOf(".")).equals(realFileNameWithoutExtension))
                                .findFirst()
                                .ifPresent(pdf -> pdfi.getPdfFiles().add(pdf));

                        // add file element
                        Element file = new Element("Page");
                        Element master = new Element("master");

                        // add image information
                        try {
                            File realFile = new File(process.getImagesOrigDirectory(false),
                                    realFileNameWithoutExtension + ".tif");
                            try (ImageManager sourcemanager = new ImageManager(realFile.toURI())) {
                                ImageInterpreter si = sourcemanager.getMyInterpreter();

                                // MimeType
                                // master.setAttribute("Format", si.getFormatType().getFormat().getMimeType());
                                master.addContent(new Element("Format").setText(si.getFormatType().getFormat().getMimeType()));

                                // Unit for the resolution, always ppi
                                // master.setAttribute("ResolutionUnit", "PPI");
                                master.addContent(new Element("ResolutionUnit").setText("PPI"));

                                // Resolution
                                // master.setAttribute("Resolution", String.valueOf(si.getOriginalImageXResolution()));
                                master.addContent(new Element("Resolution").setText(String.valueOf(si.getOriginalImageXResolution())));

                                // ColorDepth
                                // master.setAttribute("BitDepth", String.valueOf(si.getColordepth()));
                                master.addContent(new Element("BitDepth").setText(String.valueOf(si.getColordepth() * si.getSamplesperpixel())));

                                // bitonal, grey, "color"
                                // master.setAttribute("ColorSpace", si.getFormatType().getColortype().getLabel());
                                master.addContent(new Element("ColorSpace").setText(si.getFormatType().getColortype().getLabel()));

                                // Scanning device
                                master.addContent(new Element("ScanningDevice").setText(vr.replace("${process.Capturing device}")));

                                // Scanning device id
                                master.addContent(new Element("ScanningDeviceID"));

                                // Width
                                master.addContent(new Element("Width").setText(String.valueOf(si.getOriginalImageWidth())));

                                // Height
                                master.addContent(new Element("Height").setText(String.valueOf(si.getOriginalImageHeight())));

                                // Color channels (1 für grey, 3 für RGB,...)
                                // file.addContent(new
                                // Element("SamplesPerPixel").setText(String.valueOf(si.getSamplesperpixel())));
                                // jpeg- oder andere Kompression
                                // file.addContent(new
                                // Element("Compression").setText(si.getFormatType().getCompression().name()));
                                // ColorProfile available
                                // file.addContent(new
                                // Element("ColorProfile").setText(String.valueOf(si.getFormatType().isEmbeddedColorProfile())));
                                sourcemanager.close();
                            }
                        } catch (IOException | SwapException | DAOException | ImageManagerException e) {
                            log.error("Error while reading image metadata", e);
                            return false;
                        }

                        master.addContent(new Element("file").setText(exportFileName + ".tif"));
                        file.addContent(master);
                        file.addContent(new Element("alto").setText(exportFileName + ".xml").setAttribute("Format", "application/xml+alto"));
                        file.addContent(new Element("text").setText(exportFileName + ".txt").setAttribute("Format", "text/plain"));
                        files.addContent(file);

                    }
                }

                simpleXmlMap.put(targetFolder + volumeId + "-" + simpleDate + "-MI" + ".xml", doc);
                pdfIssues.add(pdfi);

                Set<String> supplementPages = new HashSet<>();
                Set<String> realSupplementPages = new HashSet<>();

                // Export each supplement on its own
                for (DocStruct supplementDs : ds.getAllChildrenAsFlatList()) {
                    if (supplementDs.getType().getName().equals(config.getString("/docstruct/supplement"))) {
                        Document supplementDoc = doc.clone();

                        Set<String> pagesToKeep = new HashSet<>();
                        List<String> realSupplementPageFileNames = new LinkedList<>();
                        List<Reference> supplementRefs = supplementDs.getAllToReferences("logical_physical");
                        if (refs != null) {
                            for (Reference ref : supplementRefs) {
                                DocStruct page = ref.getTarget();
                                String realFileName = page.getImageName();
                                String realFileNameWithoutExtension = realFileName.substring(0, realFileName.indexOf("."));
                                pagesToKeep.add(fileMap.get(realFileNameWithoutExtension));
                                realSupplementPageFileNames.add(realFileNameWithoutExtension);
                            }
                        }
                        supplementPages.addAll(pagesToKeep);
                        realSupplementPages.addAll(realSupplementPageFileNames);

                        // Remove all Page elements not belonging to this supplement
                        List<Element> pages = supplementDoc.getRootElement().getChild("Pages").getChildren("Page");
                        Iterator<Element> iterator = pages.iterator();
                        while (iterator.hasNext()) {
                            Element child = iterator.next();
                            Element master = child.getChild("master");
                            if (master != null) {
                                Element file = master.getChild("file");
                                if (file == null) {
                                    iterator.remove();
                                } else {
                                    String value = file.getTextTrim();
                                    String fileNameWithoutExtension = value.substring(0, value.lastIndexOf("."));
                                    if (!pagesToKeep.contains(fileNameWithoutExtension)) {
                                        iterator.remove();
                                    }
                                }
                            }
                        }

                        // Generate Page element numbering starting from 1
                        for (int i = 0; i < pages.size(); i++) {
                            pages.get(i).setAttribute("pg", String.format("%04d", i + 1));
                        }

                        // Update No_of_Pages value
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("No_of_Pages")
                                .setText(
                                        String.valueOf(supplementDoc.getRootElement()
                                                .getChild("Pages")
                                                .getChildren("Page")
                                                .size()));

                        String suffix = determineSupplementBasedOnIssueName(
                                Optional.ofNullable(supplementDs.getAllMetadata())
                                        .map(allMetadata -> allMetadata.stream()
                                                .filter(m -> "IssueName".equals(m.getType().getName()))
                                                .findFirst()
                                                .map(Metadata::getValue)
                                                .orElse(null))
                                        .orElse(null));

                        // Create supplement pdf file
                        PdfIssue pdfs = new PdfIssue();
                        pdfs.setFolder(targetFolder);
                        pdfs.setName(targetFolder + volumeId + "-" + simpleDate + "-" + suffix + ".pdf");
                        for (String supplementPage : realSupplementPageFileNames) {
                            pdfFiles.stream()
                                    .map(Path::toFile)
                                    .filter(f -> f.getName().substring(0, f.getName().lastIndexOf(".")).equals(supplementPage))
                                    .findFirst()
                                    .ifPresent(pdf -> pdfs.getPdfFiles().add(pdf));
                        }
                        pdfIssues.add(pdfs);

                        // Update viewer URL
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("Open_In_Viewer")
                                .setText(viewerProcessPath
                                        + supplementDs.getAllToReferences()
                                                .getFirst()
                                                .getTarget()
                                                .getAllMetadata()
                                                .stream()
                                                .filter(m -> "physPageNumber".equals(m.getType().getName()))
                                                .findFirst()
                                                .map(Metadata::getValue)
                                                .orElse(""));

                        // Update issueID
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueID")
                                .setText(volumeId + "-" + simpleDate + "-" + suffix);

                        // Update issueFile
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueFile")
                                .setText(volumeId + "-" + simpleDate + "-" + suffix + ".pdf");

                        // Update issueName
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueName")
                                .setText(AdmBsmeExportHelper.getMetdata(supplementDs, config.getString("/metadata/issueName")));

                        // Update issueNotes
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueNotes")
                                .setText(AdmBsmeExportHelper.getMetdata(supplementDs, config.getString("/metadata/issueNotes")));

                        // Update english and arabic titles
                        String supplementName = AdmBsmeExportHelper
                                .getCleanIssueLabel(AdmBsmeExportHelper.getMetdata(supplementDs, config.getString("/metadata/issueName")));
                        String supplementTitleEng = AdmBsmeExportHelper.getEnglishPartOfString(supplementName);
                        String supplementTitleAra = AdmBsmeExportHelper.getArabicPartOfString(supplementName);
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueTitleENG")
                                .setText(supplementTitleEng + "-" + date);
                        supplementDoc.getRootElement()
                                .getChild("volumeInfo")
                                .getChild("issueInfo")
                                .getChild("issueTitleARA")
                                .setText(date + "-" + supplementTitleAra);

                        simpleXmlMap.put(targetFolder + volumeId + "-" + simpleDate + "-" + suffix + ".xml", supplementDoc);
                    }
                }

                // Remove all supplement Page elements from the issue
                List<Element> pages = doc.getRootElement().getChild("Pages").getChildren("Page");
                Iterator<Element> iterator = pages.iterator();
                while (iterator.hasNext()) {
                    Element child = iterator.next();
                    Element master = child.getChild("master");
                    if (master != null) {
                        Element file = master.getChild("file");
                        if (file == null) {
                            iterator.remove();
                        } else {
                            String value = file.getTextTrim();
                            String fileNameWithoutExtension = value.substring(0, value.lastIndexOf("."));
                            if (supplementPages.contains(fileNameWithoutExtension)) {
                                iterator.remove();
                            }
                        }
                    }
                }
                pdfi.getFiles().removeIf(supplementPages::contains);
                pdfi.getPdfFiles().removeIf(pdf -> realSupplementPages.contains(pdf.getName().substring(0, pdf.getName().lastIndexOf("."))));

                // Generate Page element numbering starting from 1
                for (int i = 0; i < pages.size(); i++) {
                    pages.get(i).setAttribute("pg", String.format("%04d", i + 1));
                }

                // Update No_of_Pages value
                doc.getRootElement()
                        .getChild("volumeInfo")
                        .getChild("issueInfo")
                        .getChild("No_of_Pages")
                        .setText(
                                String.valueOf(pages.size()));
            }
        }

        boolean success = true;

        // write the newspaper METS files
        try {
            NewspaperMetsCreator nmc = new NewspaperMetsCreator(config, process, prefs, dd, fileMap);
            nmc.exportMetsFile();
        } catch (WriteException | PreferencesException | MetadataTypeNotAllowedException
                | TypeNotAllowedForParentException | IOException | SwapException | DAOException e) {
            String message = "Error writing the mets file";
            log.error(message, e);
            Helper.setFehlerMeldung(message, e);
            success = false;
        }

        // copy all important files to target folder
        try {
            AdmBsmeExportHelper.copyFolderContent(process.getImagesOrigDirectory(false), "tif", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrAltoDirectory(), "xml", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrTxtDirectory(), "txt", fileMap, targetFolder);
        } catch (IOException | SwapException | DAOException e) {
            String message = "Error while copying the image files to export folder";
            log.error(message, e);
            Helper.setFehlerMeldung(message, e);
            success = false;
        }

        // generate PDF files per issue
        for (PdfIssue pi : pdfIssues) {
            try {
                // TODO: Create pdf per issue with correct pages
                gluePDF(
                        pi.getPdfFiles(),
                        new File(pi.getName()));

                // TODO: Create pdf per supplement with correct pages

                // if a separate PDF copy shall be stored
                if (StringUtils.isNotBlank(pdfCopyFolder) && StorageProvider.getInstance().isFileExists(Paths.get(pi.getName()))) {
                    StorageProvider.getInstance()
                            .copyFile(Paths.get(pi.getName()), Paths.get(pdfCopyFolder, Paths.get(pi.getName()).getFileName().toString()));
                }

            } catch (IOException e) {
                String message = "Error while generating PDF files";
                log.error(message, e);
                Helper.setFehlerMeldung(message, e);
                success = false;
            }
        }

        // finally write all simple xml files
        for (String key : simpleXmlMap.keySet()) {
            XMLOutputter xmlOutputter = new XMLOutputter();
            xmlOutputter.setFormat(Format.getPrettyFormat());
            File xmlfile = new File(key);
            try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
                xmlOutputter.output(simpleXmlMap.get(key), fileOutputStream);
            } catch (IOException e) {
                String message = "Error writing the simple xml file";
                log.error(message, e);
                Helper.setFehlerMeldung(message, e);
                success = false;
            }
        }

        return success;
    }

    private static String determineSupplementBasedOnIssueName(String issueName) {
        if (issueName.contains("Munawat")) {
            return "MS";
        }
        if (issueName.contains("Sport")) {
            return "SS";
        }
        if (issueName.contains("Economics")) {
            return "ES";
        }
        if (issueName.contains("Other")) {
            return "OS";
        }
        if (issueName.contains("Arabic and International")) {
            return "IS";
        }
        if (issueName.contains("Culture")) {
            return "CS";
        }
        if (issueName.contains("Local")) {
            return "LS";
        }
        if (issueName.contains("Viewpoints")) {
            return "VS";
        }

        return "ZZ";
    }

}