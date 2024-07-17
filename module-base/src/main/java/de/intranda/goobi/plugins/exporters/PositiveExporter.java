package de.intranda.goobi.plugins.exporters;

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
import org.goobi.beans.JournalEntry;
import org.goobi.beans.Process;
import org.goobi.production.enums.LogType;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.intranda.goobi.plugins.AdmBsmeExportHelper;
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

@PluginImplementation
@Log4j2
public class PositiveExporter {

    private XMLConfiguration config;
    private Process process;
    private Prefs prefs;
    private DigitalDocument dd;
    private String targetFolder;

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
    public PositiveExporter(XMLConfiguration config, Process process, Prefs prefs, DigitalDocument dd) {
        this.config = config;
        config.setExpressionEngine(new XPathExpressionEngine());
        this.process = process;
        this.prefs = prefs;
        this.dd = dd;
        targetFolder = config.getString("targetDirectoryPositives", "/opt/digiverso/goobi/output/");
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
        DocStruct topStruct = dd.getLogicalDocStruct();

        // prepare process information
        Element info = new Element("envelopeInfo");
        String identifier = AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/identifier"));

        String rightsToUse = vr.replace(config.getString("/rightsToUse"));
        String rightsDetails = vr.replace(config.getString("/rightsDetails"));
        String source = vr.replace(config.getString("/source"));
        String mediaType = vr.replace(config.getString("/mediaType"));
        //String mediaGroup = vr.replace(config.getString("/mediaGroup"));
        String sourceOrganisation = vr.replace(config.getString("/sourceOrganisation"));
        String eventDate = vr.replace(config.getString("/eventDate"));
        String eventTime = vr.replace(config.getString("/eventTime"));
        String eventName = vr.replace(config.getString("/eventName"));
        String subject = vr.replace(config.getString("/subject"));
        String photographer = vr.replace(config.getString("/photographer"));
        String personsInImage = vr.replace(config.getString("/personsInImage"));
        String locations = vr.replace(config.getString("/locations"));
        String description = vr.replace(config.getString("/description"));
        String editorInChief = vr.replace(config.getString("/editorInChief"));
        //String format = vr.replace(config.getString("/format"));
        String envelopeNumber = vr.replace(config.getString("/envelopeNumber"));

        info.addContent(new Element("Rights_to_Use").setText(rightsToUse));
        info.addContent(new Element("Right_Details").setText(rightsDetails));
        info.addContent(new Element("Media_Source").setText(source));
        info.addContent(new Element("Media_Type").setText(mediaType));
        info.addContent(new Element("Envelope_Barcode").setText(identifier));
        info.addContent(new Element("Envelope_Number").setText(envelopeNumber));
        info.addContent(new Element("Publication_Name")
                .setText(AdmBsmeExportHelper.getMetdata(topStruct, config.getString("/metadata/titleLabel"))));
        info.addContent(
                new Element("Source_Organization").setText(sourceOrganisation));
        info.addContent(new Element("Event_Date").setText(eventDate));
        info.addContent(new Element("Event_Time").setText(eventTime));
        info.addContent(new Element("Event_Name").setText(eventName));
        info.addContent(new Element("Subject").setText(subject));
        info.addContent(new Element("Photographer").setText(photographer));
        info.addContent(new Element("Persons_in_Image").setText(personsInImage));
        info.addContent(new Element("Editor_in_Chief").setText(editorInChief));
        info.addContent(new Element("location").setText(locations));
        info.addContent(new Element("Description").setText(description));

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
            info.addContent(technicalNotes);
        } else {
            info.addContent(new Element("Technical_Notes").setText("- no entry available -"));
        }

        // get all Positives inside of the Envelope
        List<DocStruct> positiveList = topStruct.getAllChildren();
        if (positiveList != null) {

            // EACH POSITIVE - START
            for (DocStruct ds : positiveList) {
                // prepare xml document
                Document doc = new Document();
                doc.setRootElement(new Element("Envelope"));
                info.detach();
                doc.getRootElement().addContent(info);
                File xmlfile = null;

                // get the backprint for the positive
                String backprintText = "";
                List<? extends Metadata> mds = ds.getAllMetadataByType(prefs.getMetadataTypeByName("AdmBackprint"));
                if (mds != null && mds.size() > 0) {
                    backprintText = mds.get(0).getValue().trim();
                }

                // add file information
                Element files = new Element("Images");
                doc.getRootElement().addContent(files);

                List<Reference> refs = ds.getAllToReferences("logical_physical");
                if (refs != null) {

                    // EACH IMAGE OF CURRENT POSITIVE - START
                    for (Reference ref : refs) {

                        // Image details
                        DocStruct page = ref.getTarget();
                        String realFileName = page.getImageName();
                        String realFileNameWithoutExtension = realFileName.substring(0, realFileName.indexOf("."));

                        // get the new file name for the image and reuse if created previously
                        String exportFileName = fileMap.get(realFileNameWithoutExtension);
                        if (exportFileName == null) {
                            String counter = String.format("%03d", ++fileCounter);
                            exportFileName = identifier + "-" + counter;
                            fileMap.put(realFileNameWithoutExtension, exportFileName);
                        }

                        // define the name for the xml file for the first image only
                        if (xmlfile == null) {
                            xmlfile = new File(targetFolder + identifier + "-" + String.format("%03d", fileCounter) + ".xml");
                        }

                        // add file element
                        Element file = new Element("Image");
                        file.setAttribute("id", String.format("%03d", fileCounter));
                        file.addContent(new Element("Barcode").setText(identifier + "-" + String.format("%03d", fileCounter)));
                        file.addContent(new Element("Backprint").setText(backprintText));
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

                        try {
                            // copy image file to target folder
                            Path in = Paths.get(process.getImagesOrigDirectory(false), realFileNameWithoutExtension + ".tif");
                            Path out = Paths.get(targetFolder, exportFileName + ".tif");
                            StorageProvider.getInstance().copyFile(in, out);

                            // copy plaintext file to target folder and add it to xml
                            Path ocrPlaintextPath = Paths.get(process.getOcrTxtDirectory(), realFileNameWithoutExtension + ".txt");
                            if (StorageProvider.getInstance().isFileExists(ocrPlaintextPath)) {
                                file.addContent(new Element("text").setText(exportFileName + ".txt").setAttribute("Format", "text/plain"));
                                out = Paths.get(targetFolder, exportFileName + ".txt");
                                StorageProvider.getInstance().copyFile(ocrPlaintextPath, out);
                            } else {
                                file.addContent(new Element("text").setAttribute("Format", "text/plain"));
                            }

                        } catch (IOException | SwapException | DAOException e) {
                            log.error("Error while copying the image and ocr files to export folder", e);
                            return false;
                        }

                        files.addContent(file);

                    }
                    // EACH IMAGE OF CURRENT POSITIVE - END

                }

                // write the xml file per positive
                XMLOutputter xmlOutputter = new XMLOutputter();
                xmlOutputter.setFormat(Format.getPrettyFormat());
                try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
                    xmlOutputter.output(doc, fileOutputStream);
                } catch (IOException e) {
                    log.error("Error writing the simple xml file", e);
                    return false;
                }

            }
            // EACH POSITIVE - END

        }

        return true;
    }

}