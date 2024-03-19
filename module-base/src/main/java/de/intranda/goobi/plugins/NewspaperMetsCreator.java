package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.ExportFileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsModsImportExport;

@PluginImplementation
@Log4j2
public class NewspaperMetsCreator {

	private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
	private static final Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");
	private boolean addFileExtension = true;

	private XMLConfiguration config;
	private Process process;
	private Prefs prefs;
	private DigitalDocument dd;
	private String targetFolder;
	private VariableReplacer vr;
	private Map<String, String> fileMap;

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
	public NewspaperMetsCreator(XMLConfiguration config, Process process, Prefs prefs, DigitalDocument dd,
			Map<String, String> fileMap) {
		this.config = config;
		this.process = process;
		this.prefs = prefs;
		this.dd = dd;
		this.fileMap = fileMap;
		targetFolder = config.getString("targetDirectory", "/opt/digiverso/goobi/output/");
		vr = new VariableReplacer(dd, prefs, process, null);
	}

	/**
	 * generate the METS files
	 * 
	 * @return
	 * @throws IOException
	 * @throws WriteException
	 * @throws PreferencesException
	 * @throws MetadataTypeNotAllowedException
	 * @throws TypeNotAllowedForParentException
	 * @throws SwapException
	 * @throws DAOException 
	 */
	public boolean exportMetsFile() throws IOException, WriteException, PreferencesException,
			MetadataTypeNotAllowedException, TypeNotAllowedForParentException, SwapException, DAOException {

		String goobiId = String.valueOf(process.getId());
		config.setExpressionEngine(new XPathExpressionEngine());

		// read configuration parameters from config file
		MetadataType zdbIdAnalogType = prefs.getMetadataTypeByName(config.getString("/metadata/zdbidanalog"));
		MetadataType zdbIdDigitalType = prefs.getMetadataTypeByName(config.getString("/metadata/zdbiddigital"));
		MetadataType purlType = prefs.getMetadataTypeByName(config.getString("/metadata/purl"));

		MetadataType identifierType = prefs.getMetadataTypeByName(config.getString("/metadata/identifier"));
		MetadataType issueDateType = prefs.getMetadataTypeByName(config.getString("/metadata/issueDate"));
		MetadataType yearDateType = prefs.getMetadataTypeByName(config.getString("/metadata/yearDate"));

		MetadataType labelType = prefs.getMetadataTypeByName(config.getString("/metadata/titleLabel"));
		MetadataType mainTitleType = prefs.getMetadataTypeByName(config.getString("/metadata/modsTitle"));

		MetadataType issueNumberType = prefs.getMetadataTypeByName(config.getString("/metadata/issueNumber"));
		MetadataType sortNumberType = prefs.getMetadataTypeByName(config.getString("/metadata/sortNumber"));

		MetadataType languageType = prefs.getMetadataTypeByName(config.getString("/metadata/language"));
		MetadataType locationType = prefs.getMetadataTypeByName(config.getString("/metadata/location"));
		MetadataType accessConditionType = prefs.getMetadataTypeByName(config.getString("/metadata/licence"));

		MetadataType resourceType = prefs.getMetadataTypeByName(config.getString("/metadata/resourceType"));

		MetadataType anchorIdType = prefs.getMetadataTypeByName(config.getString("/metadata/anchorId"));
		MetadataType anchorTitleType = prefs.getMetadataTypeByName(config.getString("/metadata/anchorTitle"));
		MetadataType anchorZDBIdDigitalType = prefs
				.getMetadataTypeByName(config.getString("/metadata/anchorZDBIdDigital"));

		DocStructType newspaperType = prefs.getDocStrctTypeByName(config.getString("/docstruct/newspaper"));
		DocStructType yearType = prefs.getDocStrctTypeByName(config.getString("/docstruct/year"));
		DocStructType monthType = prefs.getDocStrctTypeByName(config.getString("/docstruct/month"));
		DocStructType dayType = prefs.getDocStrctTypeByName(config.getString("/docstruct/day"));
		DocStructType issueType = prefs.getDocStrctTypeByName(config.getString("/docstruct/issue"));

		DocStructType newspaperStubType = prefs.getDocStrctTypeByName(config.getString("/docstruct/newspaperStub"));

		String metsResolverUrl = config.getString("/metsUrl");
		addFileExtension = config.getBoolean("/metsUrl/@addFileExtension", false);
		String piResolverUrl = config.getString("/resolverUrl");

		Path tmpExportFolder = Files.createTempDirectory("mets_export");

		DocStruct logical = dd.getLogicalDocStruct();
		DocStruct oldPhysical = dd.getPhysicalDocStruct();

		// check if it is a newspaper
		if (!logical.getType().isAnchor()) {
			problems.add(logical.getType().getName() + " has the wrong type. It is not an anchor.");
			return false;
		}
		String zdbIdAnalog = null;
		String zdbIdDigital = null;
		String identifier = null;
		String titleLabel = null;
		String mainTitle = null;
		String language = null;
		String location = null;
		String accessCondition = null;

		for (Metadata md : logical.getAllMetadata()) {
			// get zdb id
			if (md.getType().equals(zdbIdAnalogType)) {
				zdbIdAnalog = md.getValue();
			}
			if (md.getType().equals(zdbIdDigitalType)) {
				zdbIdDigital = md.getValue();
			}
			// get identifier
			else if (md.getType().equals(identifierType)) {
				identifier = md.getValue();
			} else if (md.getType().equals(labelType)) {
				titleLabel = md.getValue();
			} else if (md.getType().equals(mainTitleType)) {
				mainTitle = md.getValue();
			} else if (md.getType().equals(languageType)) {
				language = md.getValue();
			} else if (md.getType().equals(locationType)) {
				location = md.getValue();
			} else if (md.getType().equals(accessConditionType)) {
				accessCondition = md.getValue();
			}
		}
		if (StringUtils.isBlank(mainTitle) && StringUtils.isNotBlank(titleLabel)) {
			Metadata md = new Metadata(mainTitleType);
			md.setValue(titleLabel);
			logical.addMetadata(md);
		}

		DocStruct volume = logical.getAllChildren().get(0);
		String volumeLabel = null;
		String volumeTitle = null;
		String publicationYear = null;
		String sortNumber = null;
		String issueNumber = null;

		for (Metadata md : volume.getAllMetadata()) {
			// get current year
			if (md.getType().equals(yearDateType)) {
				publicationYear = md.getValue();
			}
			if (md.getType().equals(labelType)) {
				volumeLabel = md.getValue();
			}
			if (md.getType().equals(mainTitleType)) {
				volumeTitle = md.getValue();
			}
			if (md.getType().equals(sortNumberType)) {
				sortNumber = md.getValue();
			}
			if (md.getType().equals(issueNumberType)) {
				issueNumber = md.getValue();
			}
			if (language == null && md.getType().equals(languageType)) {
				language = md.getValue();
			}
			if (location == null && md.getType().equals(locationType)) {
				location = md.getValue();
			}
			if (accessCondition == null && md.getType().equals(accessConditionType)) {
				accessCondition = md.getValue();
			}
		}

		if (StringUtils.isBlank(volumeTitle) && StringUtils.isNotBlank(volumeLabel)) {
			try {
				Metadata md = new Metadata(mainTitleType);
				md.setValue(volumeLabel);
				volume.addMetadata(md);
			} catch (UGHException e) {
				log.info(e);
			}
		}

		if (StringUtils.isBlank(sortNumber) && StringUtils.isNotBlank(issueNumber)
				&& StringUtils.isNumeric(issueNumber)) {
			try {
				Metadata md = new Metadata(sortNumberType);
				md.setValue(issueNumber);
				volume.addMetadata(md);
			} catch (UGHException e) {
				log.info(e);
			}
		}

		// list all issues
		List<DocStruct> issues = volume.getAllChildren();

		// create new anchor file for newspaper
		// https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Gesamtaufnahme+Zeitung+1.0

		ExportFileformat newspaperExport = new MetsModsImportExport(prefs);

		DigitalDocument anchorDigitalDocument = new DigitalDocument();
		newspaperExport.setDigitalDocument(anchorDigitalDocument);
		String anchor = config.getString("/metsPointerPathAnchor", process.getProjekt().getMetsPointerPathAnchor());
		anchor = vr.replace(anchor);
		newspaperExport.setMptrAnchorUrl(anchor);
		String pointer = config.getString("/metsPointerPath", process.getProjekt().getMetsPointerPath());
		pointer = vr.replace(pointer);
		setMetsParameter(goobiId, pointer, anchor, newspaperExport);

		DocStruct newspaper = copyDocstruct(newspaperType, logical, anchorDigitalDocument);
		anchorDigitalDocument.setLogicalDocStruct(newspaper);

		// create volume for year
		// https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Jahrgang+Zeitung+1.0
		DocStruct yearVolume = copyDocstruct(yearType, volume, anchorDigitalDocument);
		if (newspaper == null || yearVolume == null) {
			return false;
		}
		if (StringUtils.isNotBlank(publicationYear)) {
			yearVolume.setOrderLabel(publicationYear);
		}
		String yearTitle = null;
		String yearIdentifier = null;
		for (Metadata md : yearVolume.getAllMetadata()) {
			if (md.getType().equals(labelType)) {
				yearTitle = md.getValue();
			} else if (md.getType().equals(identifierType)) {
				yearIdentifier = md.getValue();
			}
		}

		try {
			newspaper.addChild(yearVolume);
		} catch (TypeNotAllowedAsChildException e) {
			problems.add("Cannot add year to newspaper");
			log.error(e);
			return false;
		}

		for (DocStruct issue : issues) {
			// create issues, link issues to day
			// https://wiki.deutsche-digitale-bibliothek.de/display/DFD/Ausgabe+Zeitung+1.0
			// export each issue

			// check if required metadata is available, otherwise add it

			String issueLabel = null;
			String issueTitle = null;
			String issueNo = null;
			String issueSortingNumber = null;
			String issueLanguage = null;
			String issueLocation = null;
			String issueLicence = null;

			String issueIdentifier = null;
			String simpleDate = null;
			String dateValue = null;
			String resource = null;
			String purl = null;
			String analogIssueZdbId = null;
			String digitalIssueZdbId = null;
			String anchorId = null;
			String anchorTitle = null;

			for (Metadata md : issue.getAllMetadata()) {
				if (md.getType().equals(anchorZDBIdDigitalType)) {
					digitalIssueZdbId = md.getValue();
				}
				if (md.getType().equals(zdbIdAnalogType)) {
					analogIssueZdbId = md.getValue();
				}

				if (md.getType().equals(anchorIdType)) {
					anchorId = md.getValue();
				}
				if (md.getType().equals(anchorTitleType)) {
					anchorTitle = md.getValue();
				}

				if (md.getType().equals(identifierType)) {
					issueIdentifier = md.getValue();
				}
				if (md.getType().equals(labelType)) {
					md.setValue(getTranslatedIssueLabels(md.getValue()));
					issueLabel = md.getValue();
				}
				if (md.getType().equals(mainTitleType)) {
					md.setValue(getTranslatedIssueLabels(md.getValue()));
					issueTitle = md.getValue();
				}
				if (md.getType().equals(issueNumberType)) {
					issueNo = md.getValue();
				}
				if (md.getType().equals(sortNumberType)) {
					issueSortingNumber = md.getValue();
				}
				if (md.getType().equals(issueDateType)) {
					dateValue = md.getValue();
				}

				if (md.getType().equals(resourceType)) {
					resource = md.getValue();
				}
				if (md.getType().equals(purlType)) {
					purl = md.getValue();
				}

				if (md.getType().equals(languageType)) {
					issueLanguage = md.getValue();
				}
				if (md.getType().equals(locationType)) {
					issueLocation = md.getValue();
				}
				if (md.getType().equals(accessConditionType)) {
					issueLicence = md.getValue();
				}

			}
			// copy metadata from anchor into the issue
			if (StringUtils.isBlank(issueTitle) && StringUtils.isNotBlank(issueLabel)) {
				try {
					Metadata md = new Metadata(mainTitleType);
					md.setValue(issueLabel);
					issue.addMetadata(md);
				} catch (UGHException e) {
					log.info(e);
				}
			}
			if (StringUtils.isBlank(issueSortingNumber) && StringUtils.isNotBlank(issueNo)
					&& StringUtils.isNumeric(issueNo)) {
				Metadata md = new Metadata(sortNumberType);
				md.setValue(issueNo);
				issue.addMetadata(md);
				issueSortingNumber = issueNo;
			}
			if (StringUtils.isBlank(issueLanguage) && StringUtils.isNotBlank(language)) {
				Metadata md = new Metadata(languageType);
				md.setValue(language);
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(issueLocation) && StringUtils.isNotBlank(location)) {
				Metadata md = new Metadata(locationType);
				md.setValue(location);
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(issueLicence) && StringUtils.isNotBlank(accessCondition)) {
				Metadata md = new Metadata(accessConditionType);
				md.setValue(accessCondition);
				issue.addMetadata(md);
			}
			if (StringUtils.isBlank(analogIssueZdbId) && StringUtils.isNotBlank(zdbIdAnalog)) {
				Metadata md = new Metadata(zdbIdAnalogType);
				md.setValue(zdbIdAnalog);
				issue.addMetadata(md);
			}
			if (StringUtils.isBlank(digitalIssueZdbId) && StringUtils.isNotBlank(zdbIdDigital)) {
				Metadata md = new Metadata(anchorZDBIdDigitalType);
				md.setValue(zdbIdDigital);
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(issueIdentifier)) {
				simpleDate = dateValue.replace("-", "");
				issueIdentifier = yearIdentifier + "-" + simpleDate;
				Metadata md = new Metadata(identifierType);
				md.setValue(issueIdentifier);
				issue.addMetadata(md);
			}
			if (StringUtils.isBlank(resource)) {
				Metadata md = new Metadata(resourceType);
				md.setValue(config.getString("/constants/mediaType"));
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(purl)) {
				Metadata md = new Metadata(purlType);
				md.setValue(piResolverUrl + yearIdentifier + "-" + dateValue.replace("-", ""));
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(anchorId)) {
				Metadata md = new Metadata(anchorIdType);
				md.setValue(identifier);
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(anchorTitle)) {
				Metadata md = new Metadata(anchorTitleType);
				md.setValue(titleLabel);
				issue.addMetadata(md);
			}

			if (StringUtils.isBlank(dateValue)) {
				problems.add("Abort export, issue has no publication date");
				return false;
			}

			if (!dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
				problems.add("Issue date " + dateValue + " has the wrong format. Expected is YYYY-MM-DD");
				return false;
			}

			if (StringUtils.isBlank(yearVolume.getOrderLabel())) {
				yearVolume.setOrderLabel(dateValue.substring(0, 4));
			}

			String monthValue = dateValue.substring(0, 7);

			DocStruct currentMonth = null;
			DocStruct currentDay = null;
			if (yearVolume.getAllChildren() != null) {
				for (DocStruct monthDocStruct : yearVolume.getAllChildren()) {
					String currentDate = monthDocStruct.getOrderLabel();
					if (monthValue.equals(currentDate)) {
						currentMonth = monthDocStruct;
						break;
					}
				}
			}
			if (currentMonth == null) {
				try {
					currentMonth = anchorDigitalDocument.createDocStruct(monthType);
					currentMonth.setOrderLabel(monthValue);

					yearVolume.addChild(currentMonth);
				} catch (TypeNotAllowedAsChildException e) {
					log.error(e);
				}
			}
			if (currentMonth.getAllChildren() != null) {
				for (DocStruct dayDocStruct : currentMonth.getAllChildren()) {
					String currentDate = dayDocStruct.getOrderLabel();
					if (dateValue.equals(currentDate)) {
						currentDay = dayDocStruct;
						break;
					}
				}
			}
			if (currentDay == null) {
				try {
					currentDay = anchorDigitalDocument.createDocStruct(dayType);
					currentDay.setOrderLabel(dateValue);
					currentMonth.addChild(currentDay);
				} catch (TypeNotAllowedAsChildException e) {
					log.error(e);
				}
			}
			try {
				DocStruct dummyIssue = anchorDigitalDocument.createDocStruct(issueType);
				dummyIssue.setOrderLabel(dateValue);
				currentDay.addChild(dummyIssue);
				if (issue.getAllMetadata() != null) {
					for (Metadata md : issue.getAllMetadata()) {
						if (md.getType().equals(labelType)) {
							Metadata label = new Metadata(labelType);
							label.setValue(md.getValue());
							dummyIssue.addMetadata(label);

						}
					}
				}
				// create identifier if missing, add zdb id if missing
				if (addFileExtension) {
					dummyIssue
							.setLink(metsResolverUrl + yearIdentifier + "-" + dateValue.replace("-", "") + "-mets.xml");
				} else {
					dummyIssue.setLink(metsResolverUrl + issueIdentifier);
				}

				ExportFileformat issueExport = new MetsModsImportExport(prefs);
				DigitalDocument issueDigDoc = new DigitalDocument();
				issueExport.setDigitalDocument(issueDigDoc);
				setMetsParameter(goobiId, pointer, anchor, issueExport);

				// create hierarchy for individual issue file
				// newspaper
				DocStruct dummyNewspaper = issueDigDoc.createDocStruct(newspaperStubType);
				if (addFileExtension) {
					dummyNewspaper.setLink(metsResolverUrl + identifier + ".xml");
				} else {
					dummyNewspaper.setLink(metsResolverUrl + identifier);
				}
				Metadata titleMd = null;
				try {
					titleMd = new Metadata(labelType);
					titleMd.setValue(titleLabel);
					dummyNewspaper.addMetadata(titleMd);
				} catch (UGHException e) {
					log.info(e);
				}
				// year
				DocStruct issueYear = issueDigDoc.createDocStruct(yearType);
				issueYear.setOrderLabel(dateValue.substring(0, 4));

				if (addFileExtension) {
					issueYear.setLink(metsResolverUrl + yearIdentifier + ".xml");
				} else {
					issueYear.setLink(metsResolverUrl + yearIdentifier);
				}
				titleMd = new Metadata(labelType);
				titleMd.setValue(yearTitle);
				try {
					issueYear.addMetadata(titleMd);
				} catch (UGHException e) {
					log.info(e);
				}
				dummyNewspaper.addChild(issueYear);

				// month
				DocStruct issueMonth = issueDigDoc.createDocStruct(monthType);
				issueMonth.setOrderLabel(monthValue);
				issueYear.addChild(issueMonth);
				// day
				DocStruct issueDay = issueDigDoc.createDocStruct(dayType);
				issueDay.setOrderLabel(dateValue);
				issueMonth.addChild(issueDay);

				// issue
				DocStruct newIssue = copyDocstruct(issueType, issue, issueDigDoc);

				// additional manual values
				addMetdata(newIssue, config.getString("/metadata/location"),
						config.getString("/constants/sourceOrganisation"));
				addMetdata(newIssue, config.getString("/metadata/accessConditionUse"),
						config.getString("/constants/rightsToUse"));
				addMetdata(newIssue, config.getString("/metadata/accessConditionDetails"),
						config.getString("/constants/rightsDetails"));
				addMetdata(newIssue, config.getString("/metadata/frequency"), config.getString("/constants/frequency"));

				issueDigDoc.setLogicalDocStruct(dummyNewspaper);

				// create physSequence
				DocStruct physicalDocstruct = issueDigDoc.createDocStruct(oldPhysical.getType());
				issueDigDoc.setPhysicalDocStruct(physicalDocstruct);

				// add images
				if (issue.getAllToReferences() != null) {
					for (Reference ref : issue.getAllToReferences()) {
						DocStruct oldPage = ref.getTarget();
						String filename = Paths.get(oldPage.getImageName()).getFileName().toString();

						DocStruct newPage = copyDocstruct(oldPage.getType(), oldPage, issueDigDoc);
						if (newPage != null) {
							newPage.setImageName(filename);
							physicalDocstruct.addChild(newPage);

							newIssue.addReferenceTo(newPage, "logical_physical");
						}
					}
				}
				issueDay.addChild(newIssue);
							

				// add a file group for the tif images
				VirtualFileGroup v = new VirtualFileGroup();
				v.setName("PRESENTATION");
				v.setPathToFiles(vr.replace("file:///opt/digiverso/viewer/media/" + identifier + "/"));
				v.setMimetype("image/tif");
				v.setFileSuffix("tif");
				v.setMainGroup(true);
				issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(v);

				VirtualFileGroup vAlto = new VirtualFileGroup();
				vAlto.setName("ALTO");
				vAlto.setPathToFiles(vr.replace("file:///opt/digiverso/viewer/media/" + identifier + "/"));
				vAlto.setMimetype("application/xml+alto");
				vAlto.setFileSuffix("xml");
				issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(vAlto);
				
				VirtualFileGroup vTxt = new VirtualFileGroup();
				vTxt.setName("TXT");
				vTxt.setPathToFiles(vr.replace("file:///opt/digiverso/viewer/media/" + identifier + "/"));
				vTxt.setMimetype("text/plain");
				vTxt.setFileSuffix("txt");
				issueExport.getDigitalDocument().getFileSet().addVirtualFileGroup(vTxt);

				// fix all file names to use the new ones
				for (ContentFile cf : issueDigDoc.getFileSet().getAllFiles()) {
					String fileName = cf.getLocation();
					String realFileNameWithoutExtension = fileName.substring(0, fileName.indexOf("."));
					String newFileName = fileMap.get(realFileNameWithoutExtension);
					cf.setLocation(newFileName + ".tif");
				}

				// export to configured folder
				String issueName = Paths
						.get(tmpExportFolder.toString(), yearIdentifier + "-" + simpleDate + "-mets.xml").toString();
				issueExport.write(issueName);
			} catch (TypeNotAllowedAsChildException e) {
				log.error(e);
			}
		}

		String newspaperName = Paths.get(tmpExportFolder.toString(), yearIdentifier + ".xml").toString();
		newspaperExport.write(newspaperName);

		// rename anchor file
		Path anchorPath = Paths.get(newspaperName.replace(".xml", "_anchor.xml"));
		Path newAnchorPath = Paths.get(tmpExportFolder.toString(), identifier + ".xml");
		StorageProvider.getInstance().move(anchorPath, newAnchorPath);

		// check if newspaper anchor file exists in destination folder
		Path existingAnchor = Paths.get(targetFolder, identifier + ".xml");
		if (StorageProvider.getInstance().isFileExists(existingAnchor)) {
			// if yes: merge anchor with existing one
			// open anchor, run through structMap
			try {
				mergeAnchorWithVolumes(existingAnchor, newAnchorPath);
			} catch (JDOMException | IOException e) {
				log.error(e);
			}
			// remove anchor file from temp folder
			StorageProvider.getInstance().deleteFile(newAnchorPath);
		}

		// move all files to export folder
		List<Path> files = StorageProvider.getInstance().listFiles(tmpExportFolder.toString());
		for (Path file : files) {
			Path dest = Paths.get(targetFolder, file.getFileName().toString());
			StorageProvider.getInstance().move(file, dest);
		}

		// delete targetDir
		StorageProvider.getInstance().deleteDir(tmpExportFolder);
		return true;
	}

	/**
	 * add a specific metadata to the given docstruct element
	 * 
	 * @param newIssue
	 * @param string
	 * @param string2
	 * @throws MetadataTypeNotAllowedException
	 */
	private void addMetdata(DocStruct ds, String type, String value) throws MetadataTypeNotAllowedException {
		Metadata md = new Metadata(prefs.getMetadataTypeByName(type));
		md.setValue(value);
		ds.addMetadata(md);
	}

	
	/**
	 * set some general mets parameters
	 * 
	 * @param goobiId
	 * @param pointer
	 * @param anchorPointer
	 * @param fileFormat
	 */
	private void setMetsParameter(String goobiId, String pointer, String anchorPointer, ExportFileformat fileFormat) {
		fileFormat.setGoobiID(goobiId);

		fileFormat.setRightsOwner(
				vr.replace(config.getString("/rightsOwner", process.getProjekt().getMetsRightsOwner())));
		fileFormat.setRightsOwnerLogo(
				vr.replace(config.getString("/rightsOwnerLogo", process.getProjekt().getMetsRightsOwnerLogo())));
		fileFormat.setRightsOwnerSiteURL(
				vr.replace(config.getString("/rightsOwnerSiteURL", process.getProjekt().getMetsRightsOwnerSite())));
		fileFormat.setRightsOwnerContact(
				vr.replace(config.getString("/rightsOwnerContact", process.getProjekt().getMetsRightsOwnerMail())));
		fileFormat.setDigiprovPresentation(vr.replace(
				config.getString("/digiprovPresentation", process.getProjekt().getMetsDigiprovPresentation())));
		fileFormat.setDigiprovReference(
				vr.replace(config.getString("/digiprovReference", process.getProjekt().getMetsDigiprovReference())));
		fileFormat.setDigiprovPresentationAnchor(vr.replace(config.getString("/digiprovPresentationAnchor",
				process.getProjekt().getMetsDigiprovPresentationAnchor())));
		fileFormat.setDigiprovReferenceAnchor(vr.replace(
				config.getString("/digiprovReferenceAnchor", process.getProjekt().getMetsDigiprovReferenceAnchor())));

		fileFormat.setMetsRightsLicense(
				vr.replace(config.getString("/rightsLicense", process.getProjekt().getMetsRightsLicense())));
		fileFormat.setMetsRightsSponsor(
				vr.replace(config.getString("/rightsSponsor", process.getProjekt().getMetsRightsSponsor())));
		fileFormat.setMetsRightsSponsorLogo(
				vr.replace(config.getString("/rightsSponsorLogo", process.getProjekt().getMetsRightsSponsorLogo())));
		fileFormat.setMetsRightsSponsorSiteURL(vr.replace(
				config.getString("/rightsSponsorSiteURL", process.getProjekt().getMetsRightsSponsorSiteURL())));

		fileFormat.setPurlUrl(vr.replace(config.getString("/purl", process.getProjekt().getMetsPurl())));
		fileFormat.setContentIDs(vr.replace(config.getString("/contentIds", process.getProjekt().getMetsContentIDs())));
		fileFormat.setMptrUrl(pointer);
		fileFormat.setMptrAnchorUrl(anchorPointer);
		fileFormat.setWriteLocal(false);
	}

	/**
	 * copy all docstruct details into another one 
	 * @param docstructType
	 * @param oldDocstruct
	 * @param dd
	 * @return
	 */
	private DocStruct copyDocstruct(DocStructType docstructType, DocStruct oldDocstruct, DigitalDocument dd) {
		// create new docstruct
		DocStruct newDocstruct = null;
		try {
			newDocstruct = dd.createDocStruct(docstructType);
		} catch (TypeNotAllowedForParentException e1) {
			log.error(e1);
			return null;
		}

		// copy metadata
		if (oldDocstruct.getAllMetadata() != null) {
			for (Metadata md : oldDocstruct.getAllMetadata()) {
				try {
					Metadata clone = new Metadata(md.getType());
					clone.setValue(md.getValue());
					clone.setAutorityFile(md.getAuthorityID(), md.getAuthorityURI(), md.getAuthorityValue());
					newDocstruct.addMetadata(clone);
				} catch (UGHException e) {
					log.info(e);
				}
			}
		}
		return newDocstruct;
	}

	
	/**
	 * some merging of metadata between anchor and volume
	 * @param oldAnchor
	 * @param newAnchor
	 * @throws JDOMException
	 * @throws IOException
	 */
	private void mergeAnchorWithVolumes(Path oldAnchor, Path newAnchor) throws JDOMException, IOException {

		List<Volume> volumes = new ArrayList<>();
		SAXBuilder parser = XmlTools.getSAXBuilder();

		// alten anchor einlesen
		Document metsDoc = parser.build(oldAnchor.toFile());
		Element metsRoot = metsDoc.getRootElement();
		List<Element> structMapList = metsRoot.getChildren("structMap", metsNamespace);
		for (Element structMap1 : structMapList) {
			if (structMap1.getAttribute("TYPE") != null && "LOGICAL".equals(structMap1.getAttributeValue("TYPE"))) {
				readFilesFromAnchor(volumes, structMap1);
			}
		}

		// neuen anchor einlesen
		Document newMetsDoc = parser.build(newAnchor.toFile());
		Element newMetsRoot = newMetsDoc.getRootElement();

		List<Element> newStructMapList = newMetsRoot.getChildren("structMap", metsNamespace);
		for (Element structMap : newStructMapList) {
			if (structMap.getAttribute("TYPE") != null && "LOGICAL".equals(structMap.getAttributeValue("TYPE"))) {
				readFilesFromNewAnchor(volumes, structMap);
			}
		}

		Collections.sort(volumes, volumeComperator);

		for (Element structMap : structMapList) {
			if (structMap.getAttribute("TYPE") != null && "LOGICAL".equals(structMap.getAttributeValue("TYPE"))) {
				writeVolumesToAnchor(volumes, structMap);
			}
		}
		XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());

		try (FileOutputStream output = new FileOutputStream(oldAnchor.toFile())) {
			outputter.output(metsDoc, output);
		}

	}

	/**
	 * read files from anchor
	 * 
	 * @param volumes
	 * @param structMap1
	 */
	private void readFilesFromAnchor(List<Volume> volumes, Element structMap1) {
		Element anchorDiv = structMap1.getChild("div", metsNamespace);
		List<Element> volumeDivList = anchorDiv.getChildren("div", metsNamespace);
		for (Element volumeDiv : volumeDivList) {
			String label = "";
			String volumeType = "";
			String url = "";
			String contentids = "";
			String order = "";
			if (volumeDiv.getAttribute("LABEL") != null) {
				label = volumeDiv.getAttributeValue("LABEL");
			}
			if (volumeDiv.getAttribute("TYPE") != null) {
				volumeType = volumeDiv.getAttributeValue("TYPE");
			}
			if (volumeDiv.getAttribute("CONTENTIDS") != null) {
				contentids = volumeDiv.getAttributeValue("CONTENTIDS");
			}
			if (volumeDiv.getAttribute("ORDER") != null) {
				order = volumeDiv.getAttributeValue("ORDER");
			}
			Element mptr = volumeDiv.getChild("mptr", metsNamespace);
			url = mptr.getAttributeValue("href", xlink);
			boolean foundVolume = false;
			for (Volume vol : volumes) {
				if (vol.getUrl().equals(url)) {
					foundVolume = true;
				}
			}
			if (!foundVolume) {
				Volume v = this.new Volume(label, volumeType, url, contentids, order);
				volumes.add(v);
			}
		}
	}

	/**
	 * Reads and adds volumes from structMap which are not already present in
	 * provided volumes list to it
	 *
	 * @param volumes
	 * @param structMap
	 * @return
	 */
	private boolean readFilesFromNewAnchor(List<Volume> volumes, Element structMap) {
		Element anchorDiv = structMap.getChild("div", metsNamespace);
		List<Element> volumeDivList = anchorDiv.getChildren("div", metsNamespace);
		for (Element volumeDiv : volumeDivList) {
			String label = "";
			String volumeType = "";
			String url = "";
			String contentids = "";
			String order = "";
			if (volumeDiv.getAttribute("LABEL") != null) {
				label = volumeDiv.getAttributeValue("LABEL");
			}
			if (volumeDiv.getAttribute("TYPE") != null) {
				volumeType = volumeDiv.getAttributeValue("TYPE");
			}
			if (volumeDiv.getAttribute("CONTENTIDS") != null) {
				contentids = volumeDiv.getAttributeValue("CONTENTIDS");
			}
			if (volumeDiv.getAttribute("ORDER") != null) {
				order = volumeDiv.getAttributeValue("ORDER");
			}
			Element mptr = volumeDiv.getChild("mptr", metsNamespace);
			url = mptr.getAttributeValue("href", xlink);
			Volume v = this.new Volume(label, volumeType, url, contentids, order);
			for (Volume vol : volumes) {
				if (vol.getUrl().replace(".xml", "").equals(url.replace(".xml", ""))) {
					// reexport, muss nicht gemerged werden
					return false;
				}
			}
			volumes.add(v);
		}
		return true;
	}

	/**
	 * Adds the entry from the provided volumes List as sub elements to provided
	 * structMap Element
	 * 
	 * @param volumes
	 * @param structMap
	 */
	static void writeVolumesToAnchor(List<Volume> volumes, Element structMap) {
		Element anchorDiv = structMap.getChild("div", metsNamespace);
		// clearing anchor document
		anchorDiv.removeChildren("div", metsNamespace);
		// creating new children
		int logId = 1;
		for (Volume vol : volumes) {
			Element child = new Element("div", metsNamespace);

			String strId = padIdString(logId);
			child.setAttribute("ID", "LOG_" + strId);
			logId++;
			if (vol.getLabel() != null && vol.getLabel().length() > 0) {
				child.setAttribute("LABEL", vol.getLabel());
			}
			if (vol.getContentids() != null && vol.getContentids().length() > 0) {
				child.setAttribute("CONTENTIDS", vol.getContentids());
			}
			if (vol.getOrder() != null && vol.getOrder().length() > 0) {
				child.setAttribute("ORDER", vol.getOrder());
				child.setAttribute("ORDERLABEL", vol.getOrder());
			}
			child.setAttribute("TYPE", vol.getType());
			anchorDiv.addContent(child);
			Element mptr = new Element("mptr", metsNamespace);
			mptr.setAttribute("LOCTYPE", "URL");
			if (!vol.getUrl().endsWith(".xml")) {
				vol.setUrl(vol.getUrl() + ".xml");
			}
			mptr.setAttribute("href", vol.getUrl(), xlink);
			child.addContent(mptr);
		}
	}

	/**
	 * adds 0 to front of passed id to ensure length of 4 digits
	 *
	 * @param logId
	 * @return
	 */
	private static String padIdString(int logId) {
		String strId = String.valueOf(logId);
		if (logId < 10) {
			strId = "000" + strId;
		} else if (logId < 100) {
			strId = "00" + strId;
		} else if (logId < 1000) {
			strId = "0" + strId;
		}
		return strId;
	}

	/**
	 * extremely simple translation method to convert German issue labes into pseudo english labels
	 * @param value
	 * @return
	 */
	private static String getTranslatedIssueLabels(String value) {
		String copy = value;
		copy = copy.replace("Ausgabe vom Montag, den", "Monday,");
		copy = copy.replace("Ausgabe vom Dienstag, den", "Tuesday,");
		copy = copy.replace("Ausgabe vom Mittwoch, den", "Wednesday,");
		copy = copy.replace("Ausgabe vom Donnerstag, den", "Thursday,");
		copy = copy.replace("Ausgabe vom Freitag, den", "Friday,");
		copy = copy.replace("Ausgabe vom Samstag, den", "Saturday,");
		copy = copy.replace("Ausgabe vom Sonntag, den", "Sunday,");

		copy = copy.replace("Januar ", "January ");
		copy = copy.replace("Februar ", "February ");
		copy = copy.replace("März ", "March ");
		copy = copy.replace("Mai ", "May ");
		copy = copy.replace("Juni ", "June ");
		copy = copy.replace("Juli ", "July ");
		copy = copy.replace("Oktober ", "October ");
		copy = copy.replace("Dezember ", "December ");
		// log.debug(value + " ----> " + copy);
		return copy;
	}

	
	/**
	 * simple comparator for volumes
	 */
	private static Comparator<Volume> volumeComperator = new Comparator<Volume>() { // NOSONAR

		@Override
		public int compare(Volume o1, Volume o2) {
			if (o1.getOrder() != null && o1.getOrder().length() > 0 && o2.getOrder() != null
					&& o2.getOrder().length() > 0) {
				return o1.getOrder().compareToIgnoreCase(o2.getOrder());
			}
			return o1.getUrl().compareToIgnoreCase(o2.getUrl());
		}
	};

	@Data
	@AllArgsConstructor
	class Volume {
		private String label;
		private String type;
		private String url;
		private String contentids = null;
		private String order = null;
	}

}