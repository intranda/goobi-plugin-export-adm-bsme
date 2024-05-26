package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.JournalEntry;
import org.goobi.beans.Process;
import org.goobi.production.enums.LogType;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageInterpreter;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import de.unigoettingen.sub.commons.contentlib.servlet.controller.GetPdfAction;
import de.unigoettingen.sub.commons.contentlib.servlet.model.ContentServerConfiguration;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Prefs;
import ugh.dl.Reference;

@PluginImplementation
@Log4j2
public class MagazineExporter {

    private XMLConfiguration config;
    private Process process;
    private Prefs prefs;
    private DigitalDocument dd;
    private String viewerUrl;
    private String targetFolder;
    private String pdfCopyFolder;

    // keep a list of all image files as they need to be renamed
    private Map<String, String> fileMap;
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
    public MagazineExporter(XMLConfiguration config, Process process, Prefs prefs, DigitalDocument dd) {
        this.config = config;
        config.setExpressionEngine(new XPathExpressionEngine());
        this.process = process;
        this.prefs = prefs;
        this.dd = dd;
        viewerUrl = config.getString("viewerUrl", "https://viewer.goobi.io");
        targetFolder = config.getString("targetDirectoryMagazines", "/opt/digiverso/goobi/output/");
        pdfCopyFolder = config.getString("pdfCopyMagazines");
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

        // prepare xml document
        Document doc = new Document();
        doc.setRootElement(new Element("magazine"));
        String simpleDate = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/issueDate")).replace("-", "");

        // add volume information
        Element volume = new Element("magazineInfo");
        doc.getRootElement().addContent(volume);
        String volumeId = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/identifier"));

        String rightsToUse = vr.replace(config.getString("/rightsToUse"));
        String rightsDetails = vr.replace(config.getString("/rightsDetails"));
        String source = vr.replace(config.getString("/source"));
        String mediaType = vr.replace(config.getString("/mediaType"));
        String mediaGroup = vr.replace(config.getString("/mediaGroup"));
        String sourceOrganisation = vr.replace(config.getString("/sourceOrganisation"));

        volume.addContent(new Element("Rights_to_Use").setText(rightsToUse));
        volume.addContent(new Element("Right_Details").setText(rightsDetails));
        volume.addContent(new Element("Media_Source").setText(source));
        volume.addContent(new Element("Media_type").setText(mediaType));
        volume.addContent(new Element("Media_Group").setText(mediaGroup));
        // not needed anymore
        // volume.addContent(new Element("Publication_ID").setText(volumeId));
        volume.addContent(new Element("Publication_Name")
                .setText(AdmBsmeExportHelper.getMetdata(anchor, config.getString("/metadata/titleLabel"))));
        volume.addContent(new Element("Language")
                .setText(AdmBsmeExportHelper.getLanguageFullname(anchor, config.getString("/metadata/language"))));
        volume.addContent(
                new Element("Source_Organization").setText(sourceOrganisation));

        // add all journal entries as technical notes
        if (process.getJournal() != null) {
            Element technicalNotes = new Element("Technical_Notes");
            for (JournalEntry je : process.getJournal()) {
                if (je.getType() == LogType.USER) {
                    technicalNotes.addContent(new Element("Entry").setAttribute("date", je.getFormattedCreationDate())
                            .setAttribute("type", je.getType().getTitle())
                            .setText(je.getFormattedContent()));
                }
            }
            volume.addContent(technicalNotes);
        } else {
            volume.addContent(new Element("Technical_Notes").setText("- no entry available -"));
        }

        // add issue information
        Element issue = new Element("issueInfo");
        volume.addContent(issue);
        issue.addContent(
                new Element("issueNumber").setText(AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/issueNumber"))));
        issue.addContent(new Element("issueID").setText(volumeId));

        // get the date and transform it from dd-mm-yyyy to yyyy-mm-dd
        String date = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/dateOfOrigin"));
        date = AdmBsmeExportHelper.convertDateFormatToYearMonthDay(date);
        issue.addContent(new Element("issueDate").setText(date));

        // get all title information
        String anchorTitle = AdmBsmeExportHelper.getMetdata(anchor, config.getString("/metadata/titleLabel"));
        String anchorTitleEng = AdmBsmeExportHelper.getEnglishPartOfString(anchorTitle);
        String anchorTitleAra = AdmBsmeExportHelper.getArabicPartOfString(anchorTitle);
        //String issueTitle = AdmBsmeExportHelper.getCleanIssueLabel(AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/titleLabel")));
        String issueDate =
                AdmBsmeExportHelper.getCleanIssueLabel(AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/dateOfOrigin")));

        // add an English title
        issue.addContent(new Element("issueTitleENG").setText(anchorTitleEng + "-" + issueDate));
        // add an Arabic title
        issue.addContent(new Element("issueTitleARA").setText(issueDate + "-" + anchorTitleAra));

        issue.addContent(new Element("Open_In_Viewer").setText(viewerUrl + volumeId));
        volume.addContent(new Element("Barcode").setText(volumeId));
        issue.addContent(new Element("issueFile").setText(volumeId + ".pdf").setAttribute("Format", "application/pdf"));
        issue.addContent(
                new Element("issueMetadataFile").setText(volumeId + "-mets.xml").setAttribute("Format", "application/xml"));

        // add file information
        Element files = new Element("Pages");
        doc.getRootElement().addContent(files);

        PdfIssue pdfi = new PdfIssue();
        pdfi.setFolder(targetFolder);
        pdfi.setName(targetFolder + volumeId + ".pdf");

        List<Reference> refs = topStruct.getAllToReferences("logical_physical");
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

                        // Scanning device id
                        String scanningDeviceId = "- no serial number available -"; //si.getMetadata().toString();
                        master.addContent(new Element("ScanningDeviceID").setText(scanningDeviceId));

                        // Width
                        master.addContent(new Element("Width").setText(String.valueOf(si.getOriginalImageWidth())));

                        // Height
                        master.addContent(new Element("Height").setText(String.valueOf(si.getOriginalImageHeight())));
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

        // write the xml file
        XMLOutputter xmlOutputter = new XMLOutputter();
        xmlOutputter.setFormat(Format.getPrettyFormat());
        File xmlfile = new File(targetFolder + volumeId + "-simple.xml");
        try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
            xmlOutputter.output(doc, fileOutputStream);
        } catch (IOException e) {
            log.error("Error writing the simple xml file", e);
            return false;
        }

        try {
            // copy all important files to target folder
            AdmBsmeExportHelper.copyFolderContent(process.getImagesOrigDirectory(false), "tif", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrAltoDirectory(), "xml", fileMap, targetFolder);
            AdmBsmeExportHelper.copyFolderContent(process.getOcrTxtDirectory(), "txt", fileMap, targetFolder);
            StorageProviderInterface sp = StorageProvider.getInstance();

            // rename the regular METS file
            Path pmets = Paths.get(targetFolder, volumeId + "-mets.xml");
            if (sp.isFileExists(pmets)) {
                sp.deleteFile(pmets);
            }
            sp.renameTo(Paths.get(targetFolder, process.getTitel() + ".xml"), pmets.toString());

            // rename the regular anchor METS file
            Path panchor = Paths.get(targetFolder, volumeId + "-mets_anchor.xml");
            if (sp.isFileExists(panchor)) {
                sp.deleteFile(panchor);
            }
            sp.renameTo(Paths.get(targetFolder, process.getTitel() + "_anchor.xml"),
                    panchor.toString());

            // rename the simple xml file
            Path psimple = Paths.get(targetFolder, volumeId + ".xml");
            if (sp.isFileExists(psimple)) {
                sp.deleteFile(psimple);
            }
            sp.renameTo(Paths.get(targetFolder, volumeId + "-simple.xml"),
                    psimple.toString());

        } catch (IOException | SwapException | DAOException e) {
            log.error("Error while copying the image files to export folder", e);
            return false;
        }

        // generate PDF files per issue
        try {
            Map<String, String> map = pdfi.getAsMap();
            FileOutputStream fout;
            fout = new FileOutputStream(pdfi.getName());
            new GetPdfAction().writePdf(map, ContentServerConfiguration.getInstance(), fout);
            fout.close();

            // if a separate PDF copy shall be stored
            if (StringUtils.isNotBlank(pdfCopyFolder)) {
                StorageProvider.getInstance().copyFile(Paths.get(pdfi.getName()), Paths.get(pdfCopyFolder, volumeId + ".pdf"));
            }

        } catch (IOException | ContentLibException e) {
            log.error("Error while generating PDF files", e);
            return false;
        }

        return true;
    }

}