package de.intranda.goobi.plugins.exporters;

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
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static de.intranda.goobi.plugins.AdmBsmeExportHelper.createTechnicalNotesElementFromRelevantJournalEntries;
import static de.intranda.goobi.plugins.AdmBsmeExportHelper.gluePDF;

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
        fileMap = new HashMap<String, String>();
        HashMap<String, Document> simpleXmlMap = new HashMap<String, Document>();
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
                issue.addContent(new Element("issueID").setText(volumeId + "-" + simpleDate));
                issue.addContent(new Element("issueFrequency").setText(frequency));

                // get all title information
                String anchorTitle = AdmBsmeExportHelper.getMetdata(anchor, config.getString("/metadata/titleLabel"));
                String anchorTitleEng = AdmBsmeExportHelper.getEnglishPartOfString(anchorTitle);
                String anchorTitleAra = AdmBsmeExportHelper.getArabicPartOfString(anchorTitle);
                String issueTitle =
                        AdmBsmeExportHelper.getCleanIssueLabel(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/titleLabel")));

                // convert date from from yyyy-mm-dd to dd-mm-yyyy
                String date = AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueDate"));
                date = AdmBsmeExportHelper.convertDateFormatToDayMonthYear(date);

                // add an English title
                issue.addContent(new Element("issueTitleENG").setText(anchorTitleEng + "-" + date));
                // add an Arabic title
                issue.addContent(new Element("issueTitleARA").setText(date + "-" + anchorTitleAra));

                issue.addContent(new Element("issueDate").setText(AdmBsmeExportHelper.getMetdata(ds, config.getString("/metadata/issueDate"))));
                issue.addContent(new Element("Open_In_Viewer").setText(viewerUrl + volumeId + "-" + simpleDate));
                issue.addContent(new Element("issueFile").setText(volumeId + "-" + simpleDate + ".pdf").setAttribute("Format", "application/pdf"));
                issue.addContent(
                        new Element("MetadataMetsFile").setText(volumeId + "-" + simpleDate + "-mets.xml").setAttribute("Format", "application/xml"));

                // add file information
                Element files = new Element("Pages");
                doc.getRootElement().addContent(files);

                PdfIssue pdfi = new PdfIssue();
                pdfi.setFolder(targetFolder);
                pdfi.setName(targetFolder + volumeId + "-" + simpleDate + ".pdf");

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

                        // add file element
                        Element file = new Element("Page");
                        file.setAttribute("pg", String.format("%04d", fileCounter));
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
                                master.addContent(new Element("BitDepth").setText(String.valueOf(si.getColordepth())));

                                // bitonal, grey, "color"
                                // master.setAttribute("ColorSpace", si.getFormatType().getColortype().getLabel());
                                master.addContent(new Element("ColorSpace").setText(si.getFormatType().getColortype().getLabel()));

                                // Scanning device
                                master.addContent(new Element("ScanningDevice").setText(vr.replace("${process.Capturing device}")));

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

                simpleXmlMap.put(targetFolder + volumeId + "-" + simpleDate + ".xml", doc);
                pdfIssues.add(pdfi);
            }
        }

        // write the newspaper METS files
        try {
            NewspaperMetsCreator nmc = new NewspaperMetsCreator(config, process, prefs, dd, fileMap);
            nmc.exportMetsFile();
        } catch (WriteException | PreferencesException | MetadataTypeNotAllowedException
                | TypeNotAllowedForParentException | IOException | SwapException | DAOException e) {
            log.error("Error writing the mets file", e);
            Helper.setFehlerMeldung("Error writing the mets file", e);
            return false;
        }

        // copy all important files to target folder
        try {
            AdmBsmeExportHelper.copyFolderContent(process.getImagesOrigDirectory(false), "tif", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrAltoDirectory(), "xml", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrTxtDirectory(), "txt", fileMap, targetFolder);
        } catch (IOException | SwapException | DAOException e) {
            log.error("Error while copying the image files to export folder", e);
            return false;
        }

        // generate PDF files per issue
        for (PdfIssue pi : pdfIssues) {
            try {
                gluePDF(
                        StorageProvider.getInstance().listFiles(process.getOcrPdfDirectory()).stream()
                                .map(p -> new File(p.toString()))
                                .collect(Collectors.toList()),
                        new File(pi.getName())
                );

                // if a separate PDF copy shall be stored
                if (StringUtils.isNotBlank(pdfCopyFolder)) {
                    StorageProvider.getInstance()
                            .copyFile(Paths.get(pi.getName()), Paths.get(pdfCopyFolder, Paths.get(pi.getName()).getFileName().toString()));
                }

            } catch (IOException | SwapException e) {
                log.error("Error while generating PDF files", e);
                return false;
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
                log.error("Error writing the simple xml file", e);
                return false;
            }
        }

        return true;
    }

}