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
import org.goobi.beans.Process;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageInterpreter;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import jnr.ffi.Struct.fsblkcnt_t;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.FileSet;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
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
	private VariableReplacer replacer;
	
	// keep a list of all image files as they need to be renamed
	private Map<String, String> fileMap;
	private int fileCounter;

	@Getter
	private List<String> problems;

	/**
	 * Constructor
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
		targetFolder = config.getString("targetDirectory", "/opt/digiverso/goobi/output/");
		replacer = new VariableReplacer(dd, prefs, process, null);
	}

	/**
	 * Do the actual export for a newspaper volume
	 * 
	 * @param process
	 * @param destination
	 * @return
	 */
	public boolean startExport() {
		problems = new ArrayList<>();
		fileMap = new HashMap<String, String>();
		fileCounter = 0;
		log.debug("Export directory for AdmBsmeExportPlugin: " + targetFolder);

		// in case it is an anchor file get the first childe
		DocStruct anchor = dd.getLogicalDocStruct();
		DocStruct topStruct;
		if (anchor.getType().isAnchor() && anchor.getAllChildren() != null
				&& !anchor.getAllChildren().isEmpty()) {
			topStruct = anchor.getAllChildren().get(0);
		} else {
			return false;
		}

		// run through all NewspaperIssues
		for (DocStruct ds : topStruct.getAllChildrenAsFlatList()) {
			if (ds.getType().getName().equals(config.getString("/docstruct/issue"))) {

				// prepare xml document
				Document doc = new Document();
				doc.setRootElement(new Element("newspaper"));

				// add volume information
				Element volume = new Element("volume");
				doc.getRootElement().addContent(volume);
				String volumeId = getMetdata(topStruct, config.getString("/metadata/identifier"));
				volume.addContent(new Element("rights_to_use").setText(config.getString("/constants/rightsToUse")));
				volume.addContent(new Element("right_details").setText(config.getString("/constants/rightsDetails")));
				volume.addContent(new Element("source").setText(config.getString("/constants/source")));
				volume.addContent(new Element("media_type").setText(config.getString("/constants/mediaType")));
				volume.addContent(new Element("publication_name").setText(getMetdata(anchor, config.getString("/metadata/titleLabel"))));
				volume.addContent(new Element("language").setText(getLanguageFullname(topStruct, config.getString("/metadata/issueNumber"))));
				volume.addContent(new Element("source_organization").setText(config.getString("/constants/sourceOrganisation")));
				volume.addContent(new Element("source_id").setText(volumeId));
				volume.addContent(new Element("technical_notes").setText(getMetdata(topStruct, config.getString("/metadata/technicalNotes"))));

				// add issue information
				Element issue = new Element("issue");
				doc.getRootElement().addContent(issue);
				String simpleDate = getMetdata(ds, config.getString("/metadata/issueDate")).replace("-", "");
				issue.addContent(new Element("issue_date").setText(simpleDate));
				issue.addContent(new Element("issue_number").setText(getMetdata(ds, config.getString("/metadata/issueNumber"))));
				issue.addContent(new Element("issue_frequency").setText(config.getString("/constants/frequency")));
				issue.addContent(new Element("open_in_viewer")
						.setText(viewerUrl + volumeId + "_" + simpleDate));

				// add file information
				Element files = new Element("files");
				doc.getRootElement().addContent(files);
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

						// add file element
						Element file = new Element("file");
						file.setAttribute("id", String.format("%04d", fileCounter));
						file.addContent(new Element("name").setText(exportFileName + ".tif"));

						// add image information
						try {
							File realFile = new File(process.getImagesOrigDirectory(false),
									realFileNameWithoutExtension + ".tif");
							ImageManager sourcemanager = new ImageManager(realFile.toURI());
							ImageInterpreter si = sourcemanager.getMyInterpreter();
							// MimeType
							file.addContent(new Element("file_format").setText(si.getFormatType().getFormat().getMimeType()));
							// Unit for the resolution, always ppi
							file.addContent(new Element("resolution_unit").setText("PPI"));
							// Resolution
							file.addContent(new Element("resolution").setText(String.valueOf(si.getOriginalImageXResolution())));
							// ColorDepth
							file.addContent(new Element("bit_depth").setText(String.valueOf(si.getColordepth())));
							// Scanning device
							file.addContent(new Element("scanning_device").setText("undefined"));
							// Scanning device id
							file.addContent(new Element("scanning_device_id").setText("undefined"));
							// Width
							file.addContent(new Element("width").setText(String.valueOf(si.getOriginalImageWidth())));
							// Height
							file.addContent(new Element("height").setText(String.valueOf(si.getOriginalImageHeight())));
							// bitonal, grey, "color"
							file.addContent(new Element("color_space").setText(si.getFormatType().getColortype().getLabel()));		
							// Color channels (1 für grey, 3 für RGB,...)
							// file.addContent(new Element("SamplesPerPixel").setText(String.valueOf(si.getSamplesperpixel())));
							// jpeg- oder andere Kompression
							// file.addContent(new Element("Compression").setText(si.getFormatType().getCompression().name()));
							// ColorProfile available
							// file.addContent(new Element("ColorProfile").setText(String.valueOf(si.getFormatType().isEmbeddedColorProfile())));
						} catch (IOException | SwapException | DAOException | ImageManagerException e) {
							log.error("Error while reading image metadata", e);
							return false;
						}
						files.addContent(file);

					}
				}

				// write the xml file
				XMLOutputter xmlOutputter = new XMLOutputter();
				xmlOutputter.setFormat(Format.getPrettyFormat());
				File xmlfile = new File(targetFolder + volumeId + "-" + simpleDate + "-simple.xml");
				try (FileOutputStream fileOutputStream = new FileOutputStream(xmlfile)) {
					xmlOutputter.output(doc, fileOutputStream);
				} catch (IOException e) {
					log.error("Error writing the simple xml file", e);
					return false;
				}
				
				
				// adapt file names for references
//				DocStruct phys = dd.getPhysicalDocStruct();
//				for (DocStruct page : phys.getAllChildren()) {
//					page.setImageName(page.getImageName() + "_steffen_war_hier");
//					
//					for(ContentFile cf : page.getAllContentFiles()) {
//						cf.setLocation(cf.getLocation() + "_robert_war_hier");
//					}
//					
//					
//					log.debug(page.getImageName());
//				}
				
				FileSet fs = dd.getFileSet();
				for (ContentFile cf : fs.getAllFiles()) {
					cf.setLocation(cf.getLocation() + "_robert_war_hier");
				}
				
				NewspaperMetsCreator nmc = new NewspaperMetsCreator(config, process, prefs, dd);
				try {
					nmc.exportMetsFile(ds);
				} catch (WriteException | PreferencesException | MetadataTypeNotAllowedException
						| TypeNotAllowedForParentException | IOException | SwapException e) {
					log.error("Error writing the mets file", e);
					Helper.setFehlerMeldung("Error writing the mets file", e);
					return false;
				}

			}
		}

		// copy all important files to target folder
		try {
			copyFolderContent(process.getImagesOrigDirectory(false), "tif");
			copyFolderContent(process.getOcrAltoDirectory(), "xml");
			copyFolderContent(process.getOcrTxtDirectory(), "txt");
		} catch (IOException | SwapException | DAOException e) {
			log.error("Error while copying the image files to export folder", e);
			return false;
		}
		
		return true;
	}

	/**
	 * copy files to target directory
	 * 
	 * @param ds
	 * @throws IOException
	 */
	private void copyFolderContent(String sourcefolder, String ext) throws IOException {
		for (Path pathIn : StorageProvider.getInstance().listFiles(sourcefolder)) {
			String fileIn = pathIn.getFileName().toString();
			fileIn = fileIn.substring(0, fileIn.indexOf("."));
			String fileOut = fileMap.get(fileIn);
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
	private String getMetdata(DocStruct ds, String field) {
		// run through all metadata to find the right one
		for (Metadata md : ds.getAllMetadata()) {
			if (md.getType().getName().equals(field)) {
				return md.getValue();
			}
		}
		return "";
	}

	/**
	 * get the language from metadata
	 * 
	 * @param ds
	 */
	private String getLanguageFullname(DocStruct ds, String field) {
		String lang = getMetdata(ds, field);
		switch (lang) {
		case "ara":
			return "Arabic";
		case "ger":
			return "German";
		case "eng":
			return "English";
		}
		return lang;
	}

}