---
title: Individueller Export für das DMS Imagen Media Archive Management
identifier: intranda_export_adm_bsme
description: Export Plugin für Goobi workflow zum Erzeugen spezieller Exportformate in die Software Imagen Media Archive Management
published: true
---

## Einführung
Die vorliegende Dokumentation beschreibt die Installation, Konfiguration und den Einsatz des Export-Plugins für die Erzeugung spezieller Exportpakete für die Software Imagen Media Archive Management. Innerhalb des Plugins werden hierbei derzeit 5 spezielle Publikationstypen berücksichtigt und jeweils individuell verarbeitet.

## Installation
Um das Plugin nutzen zu können, müssen folgende Dateien installiert werden:

```bash
/opt/digiverso/goobi/plugins/export/plugin-export-adm-bsme-base.jar
/opt/digiverso/goobi/config/plugin_intranda_export_adm_bsme.xml
```

Nach der Installation des Plugins kann dieses innerhalb des Workflows für die jeweiligen Arbeitsschritte ausgewählt und somit automatisch ausgeführt werden. Ein Workflow könnte dabei beispielhaft wie folgt aussehen:

![Beispielhafter Aufbau eines Workflows](screen1_de.png)

Für die Verwendung des Plugins muss dieses in einem Arbeitsschritt ausgewählt sein:

![Konfiguration des Arbeitsschritts für den Export](screen2_de.png)


## Überblick und Funktionsweise
Dieses Plugin wird innerhalb des Workflows automatisch als Export-Plugin ausgeführt und erzeugt innerhalb eines konfigurierten Verzeichnisses die jeweils benötigten Daten. Dabei handelt es sich je nach Publikationstyp um:

- Bilddateien
- Plaintext-Dateien mit OCR-Ergebnissen
- ALTO-Dateien mit OCR-Ergebnissen
- METS-Dateien
- METS-Anchor-Dateien
- XML-Export-Dateien

![Beispielhafter Einblick in ein Exportverzeichnis für mehrere Publikationstypen](screen3.png)

Insbesondere der Aufbau der XML-Export-Dateien ist je nach Publikationstyp sehr unterschiedlich. Hier einmal ein Beispiel für ein `Generic Print`-Publikationstyp:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<image>
  <ImageInfo>
    <Rights_to_Use>Yes</Rights_to_Use>
    <Right_Details>ADMN</Right_Details>
    <Media_Source>Goobi</Media_Source>
    <Media_Type>Image</Media_Type>
    <Publication_Name>الاتحاد - Al Ittihad</Publication_Name>
    <Source_Organization>Source Organization information</Source_Organization>
    <Barcode>123456789</Barcode>
    <Subject>Subject information</Subject>
    <Event_Date>2024-04-10</Event_Date>
    <Event_Name>Event Name information</Event_Name>
    <Photographer>Photographer information</Photographer>
    <Format>35mm</Format>
    <Persons_in_Image>Persons in Image information</Persons_in_Image>
    <location>Event Locations information</location>
    <Description>Description information</Description>
    <Backprint>Backprint information</Backprint>
    <Technical_Notes />
  </ImageInfo>
  <Files>
    <master>
      <Format>image/tiff</Format>
      <ResolutionUnit>PPI</ResolutionUnit>
      <Resolution>200.0</Resolution>
      <BitDepth>8</BitDepth>
      <ColorSpace>color</ColorSpace>
      <ScanningDevice>Bookeye 5</ScanningDevice>
      <ScanningDeviceID />
      <Width>1272</Width>
      <Height>1680</Height>
      <file>123456789-0001.tif</file>
    </master>
    <master>
      <Format>image/tiff</Format>
      <ResolutionUnit>PPI</ResolutionUnit>
      <Resolution>200.0</Resolution>
      <BitDepth>8</BitDepth>
      <ColorSpace>color</ColorSpace>
      <ScanningDevice>Bookeye 5</ScanningDevice>
      <ScanningDeviceID />
      <Width>1272</Width>
      <Height>1680</Height>
      <file>123456789-0002.tif</file>
    </master>
    <text Format="text/plain">123456789-0002.txt</text>
    <master>
      <Format>image/tiff</Format>
      <ResolutionUnit>PPI</ResolutionUnit>
      <Resolution>200.0</Resolution>
      <BitDepth>8</BitDepth>
      <ColorSpace>color</ColorSpace>
      <ScanningDevice>Bookeye 5</ScanningDevice>
      <ScanningDeviceID />
      <Width>1192</Width>
      <Height>1608</Height>
      <file>123456789-0003.tif</file>
    </master>
    <text Format="text/plain">123456789-0003.txt</text>
  </Files>
</image>
```

## Konfiguration
Die Konfiguration des Plugins erfolgt in der Datei `plugin_intranda_export_adm_bsme.xml` wie hier aufgezeigt:

{{CONFIG_CONTENT}}

Die darin verwendeten Parameter werden hier detailliert: 

Parameter                   | Erläuterung
----------------------------|----------------------------------------
`targetDirectoryNewspapers` | Zielverzeichnis für Zeitungen
`targetDirectoryMagazines`  | Zielverzeichnis für Zeitschriften
`targetDirectoryPositives`  | Zielverzeichnis für Positives
`targetDirectoryNegatives`  | Zielverzeichnis für Negative
`targetDirectorySlides`     | Zielverzeichnis für Slides
`targetDirectoryGeneric`    | Zielverzeichnis für Generic Prints
`pdfCopyNewspapers`         | Zielverzeichnis zur Generierung von PDF-Dateien für Zeitungen
`pdfCopyMagazines`          | Zielverzeichnis zur Generierung von PDF-Dateien für Zeitschriften
`viewerUrl`                 | URL für den Goobi viewer
`rightsToUse`               | Angabe von Nutzungsrechten
`rightsDetails`             | Details über die Nutzungsrechte
`source`                    | Angabe der Quelle der Digitalisate
`mediaType`                 | Typ der Medien
`sourceOrganisation`        | Organisation, die für die Inhalte verantwortlich ist
`frequency`                 | Erscheinungshäufigkeit
`eventName`                 | Nennung des dokumentierten Ereignisses
`eventDate`                 | Angabe des Datums, wann das Ereignis stattfand
`eventTime`                 | Angabe des Uhrzeit, wann das Ereignis stattfand
`subject`                   | Allgemeine Schlagworte
`subjectArabic`             | Angabe der Schlagworte in Arabisch
`subjectEnglish`            | Angabe der Schlagworte in Englisch
`photographer`              | Informationen zum Fotografen des Bildes
`personsInImage`            | Abgebildete Personen im Bild
`locations`                 | Angabe zum Ort der Aufnahme
`description`               | Erläuterungen und Beschreibungen zur Aufnahme
`editorInChief`             | Verantwortlicher Herausgeber
`format`                    | Formatinformationen
`envelopeNumber`            | Identifier des Umschlags, in dem die Dokumente aufbewahrt werden
`backprint`                 | Informationen über Inhalte auf der Rückseite


Für eine einfachere Inbetriebnahme befindet sich in `install`-Ordner des Plugins einn Verzeichnis mit den zwei passende Regelsätze als Referenz, die zu der hier aufgeführte Konfigurationsdatei passen.
