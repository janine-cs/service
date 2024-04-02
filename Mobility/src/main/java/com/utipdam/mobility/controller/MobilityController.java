package com.utipdam.mobility.controller;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.utipdam.mobility.FileUploadUtil;
import com.utipdam.mobility.business.DatasetDefinitionBusiness;
import com.utipdam.mobility.business.DatasetBusiness;
import com.utipdam.mobility.config.RestTemplateClient;
import com.utipdam.mobility.model.*;
import com.utipdam.mobility.model.entity.Dataset;
import com.utipdam.mobility.model.entity.DatasetDefinition;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.validator.GenericValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Date;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
public class MobilityController {
    private static final Logger logger = LoggerFactory.getLogger(MobilityController.class);
    private final String START_TIME = "first_time_seen";
    private final String RESOLUTION = "daily";
    private final String DATE_FORMAT = "yyyy-MM-dd";

    @Value("${utipdam.app.maxFileSize}")
    private long MAX_FILE_SIZE;

    @Autowired
    private DatasetDefinitionBusiness datasetDefinitionBusiness;

    @Autowired
    private DatasetBusiness datasetBusiness;

    @PostMapping("/mobility/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestPart String k,
                                                      @RequestPart MultipartFile file) {

        Map<String, Object> response = new HashMap<>();
        String errorMessage;
        if (!isNumeric(k) || (Integer.parseInt(k) < 0 || Integer.parseInt(k) > 100)) {
            errorMessage = "k must be a number between 0 - 100. You provided " + k;
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        if (!Objects.requireNonNull(file.getOriginalFilename()).endsWith(".csv")) {
            errorMessage = "Please upload a csv file. You provided " + file.getOriginalFilename();
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            errorMessage = "Exceeded max file size " + MAX_FILE_SIZE;
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        List<FileUploadResponse> listResponse = new ArrayList<>();
        DatasetDefinitionDTO dto = new DatasetDefinitionDTO();
        dto.setName("dash-upload-" + getRandomNumberString());
        dto.setDescription("Dataset uploaded from the web channel");
        dto.setCountryCode("KR");
        dto.setFee(0.0);

        DatasetDefinition ds = datasetDefinitionBusiness.save(dto);

        File fOrg;
        String path = "/data/mobility/" + ds.getId();
        try {
            fOrg = new File(path);
            fOrg.setReadable(true, false);
            fOrg.setWritable(true, false);
            fOrg.mkdirs();
            Files.setPosixFilePermissions(Path.of("/data/mobility/" + ds.getId()), PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (IOException e) {
            errorMessage = e.getMessage();
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }


        Path uploadPath = Paths.get(path);
        String fileName;
        BufferedReader br;
        String csvDate = null;

        try {
            String line;
            InputStream is = file.getInputStream();
            br = new BufferedReader(new InputStreamReader(is));
            line = br.readLine();
            String[] nextRecord;
            if (line != null) {
                nextRecord = line.split(",");
                int dateIndex = Arrays.asList(nextRecord).indexOf(START_TIME);
                if (dateIndex < 0) {
                    dateIndex = Arrays.asList(nextRecord).indexOf("start_time");
                }

                if (dateIndex > 0 && (line = br.readLine()) != null) {
                    csvDate = line.split(",")[dateIndex];
                    csvDate = csvDate.split(" ")[0];
                    if (!GenericValidator.isDate(csvDate, DATE_FORMAT, true)) {
                        errorMessage = "start_time must be yyyy-MM-dd HH:mm:ss format";
                        logger.error(errorMessage);
                        response.put("error", errorMessage);
                        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
                    }
                }
            }

        } catch (IOException e) {
            errorMessage = e.getMessage();
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (csvDate != null) {
            UUID uuid = UUID.randomUUID();
            fileName = "dataset-" + uuid + "-" + csvDate + ".csv";

            //process anonymization


            long dataPoints;
            try {
                FileUploadUtil.saveFile(fileName, file, uploadPath);
            } catch (IOException e) {
                errorMessage = e.getMessage();
                logger.error(errorMessage);
                response.put("error", errorMessage);
                return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            File fi = new File(path + "/" + fileName);
            fi.setReadable(true, false);
            fi.setWritable(true, false);

            Path filePath = Paths.get(uploadPath + "/" + fileName);
            try (Stream<String> stream = Files.lines(filePath)) {
                dataPoints = stream.count() - 1;
                Dataset dataset = new Dataset();
                dataset.setId(uuid);
                dataset.setDatasetDefinition(ds);
                if (csvDate != null) {
                    dataset.setStartDate(Date.valueOf(csvDate));
                    dataset.setEndDate(Date.valueOf(csvDate));
                }
                dataset.setResolution(RESOLUTION);
                dataset.setK(Integer.parseInt(k));
                dataset.setDataPoints(dataPoints);

                Dataset d = datasetBusiness.save(dataset);

                long size = Files.size(filePath);

                FileUploadResponse fileUploadResponse = new FileUploadResponse();
                fileUploadResponse.setDatasetDefinitionId(ds.getId());
                fileUploadResponse.setDatasetId(d.getId());
                fileUploadResponse.setFileName(fileName);
                fileUploadResponse.setSize(size);
                listResponse.add(fileUploadResponse);
                response.put("data", listResponse);
                return new ResponseEntity<>(response, HttpStatus.OK);

            } catch (IOException e) {
                errorMessage = e.getMessage();
                logger.error(errorMessage);
                response.put("error", errorMessage);
                return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);

    }

    public static boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @GetMapping("/mobility/download")
    public ResponseEntity<Resource> download(@RequestParam UUID datasetId) {
        HttpHeaders responseHeaders = new HttpHeaders();
        Optional<Dataset> dataset = datasetBusiness.getById(datasetId);
        if (dataset.isPresent()) {
            Dataset datasetObj = dataset.get();
            Optional<DatasetDefinition> df = datasetDefinitionBusiness.getById(datasetObj.getDatasetDefinition().getId());

            if (df.isPresent()) {
                DatasetDefinition definitionObj = df.get();
                if (definitionObj.getInternal() == null || !definitionObj.getInternal()) {
                    logger.info(definitionObj.getInternal().toString());
                    String path = "/data/mobility/" + datasetObj.getDatasetDefinition().getId();
                    File dir = new File(path);
                    FileFilter fileFilter = new WildcardFileFilter("*dataset-" + datasetId + "-*");
                    File[] files = dir.listFiles(fileFilter);
                    if (files != null) {
                        for (File fi : files) {

                            try {
                                BufferedReader file = new BufferedReader(
                                        new InputStreamReader(new FileSystemResource(fi).getInputStream()));
                                StringBuffer inputBuffer = new StringBuffer();
                                String line;

                                while ((line = file.readLine()) != null) {
                                    inputBuffer.append(line);
                                    inputBuffer.append('\n');
                                }
                                file.close();
                                String inputStr = inputBuffer.toString();

                                ContentDisposition contentDisposition = ContentDisposition.builder("inline")
                                        .filename(fi.getName())
                                        .build();
                                responseHeaders.setContentDisposition(contentDisposition);
                                InputStream stream = new ByteArrayInputStream(inputStr.getBytes(StandardCharsets.UTF_8));
                                InputStreamResource resource = new InputStreamResource(stream);
                                return ResponseEntity.ok()
                                        .headers(responseHeaders)
                                        .contentLength(stream.available())
                                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                        .body(resource);
                            } catch (IOException e) {
                                logger.error(e.getMessage());
                                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                            }
                        }
                    }
                } else {
                    logger.info("downloading from internal server");
                    //download from internal archive server
                    RestTemplateClient restTemplate = new RestTemplateClient();
                    String domain = df.get().getServer().getDomain();

                    if (domain != null) {
                        String uri = domain + "/internal/mobility/download";
                        logger.info(uri);
                        String url = UriComponentsBuilder
                                .fromUriString(uri)
                                .queryParam("datasetDefinitionId", definitionObj.getId())
                                .queryParam("datasetId", datasetObj.getId())
                                .build().toUriString();
                        try {
                            return restTemplate.restTemplate().exchange(url,
                                    HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                                    });

                        } catch (Exception ex) {
                            logger.error(ex.getMessage());
                            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                        }
                    }

                }
            }
        }

        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @GetMapping("/mobility/visitorDetection")
    public ResponseEntity<Map<String, Object>> findMeHere(@RequestParam Integer[] locationIds,
                                                          @RequestParam UUID datasetId) {
        Map<String, Object> response = new HashMap<>();
        String errorMessage;
//        if (locationIds.length < 2) {
//            errorMessage = "Please select 2 or more locations";
//            logger.error(errorMessage);
//            response.put("error", errorMessage);
//            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
//        }

        if (Arrays.stream(locationIds).anyMatch(item -> item == 0)) {
            errorMessage = "location id 0 is invalid";
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        List<Integer> locIdList = Arrays.stream(locationIds).toList();
        Optional<Dataset> datasetIdCheck = datasetBusiness.getById(datasetId);
        if (datasetIdCheck.isPresent()) {
            Dataset dataset = datasetIdCheck.get();
            Optional<DatasetDefinition> dd = datasetDefinitionBusiness.getById(dataset.getDatasetDefinition().getId());
            if (dd.isPresent()) {
                DatasetDefinition datasetDef = dd.get();
                List<VisitorTracks> visitorTracks = new ArrayList<>();
                RFC4180Parser rfc4180Parser = new RFC4180ParserBuilder().build();

                if (datasetDef.getInternal() == null || !datasetDef.getInternal()) {
                    File file = new File("/data/mobility/" + datasetDef.getId());
                    File[] files = file.listFiles((d, name) -> name.contains(dataset.getId().toString()));
                    if (files != null && files.length > 0) {
                        if (files[0].getName().contains(dataset.getId().toString())) {
                            try (CSVReader csvReader = new CSVReaderBuilder(new FileReader(files[0])).withSkipLines(1).withCSVParser(rfc4180Parser).build()) {
                                String[] nextRecord;

                                while ((nextRecord = csvReader.readNext()) != null) {
                                    VisitorTracks visitorTrack = createVisitorTrack(nextRecord);
                                    if (visitorTrack != null) {
                                        visitorTracks.add(visitorTrack);
                                    }
                                }
                            } catch (IOException | CsvValidationException e) {
                                errorMessage = e.getMessage();
                                logger.error(errorMessage);
                                response.put("error", errorMessage);
                                return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
                            }
                            visitorTracks.sort(Comparator.comparing(VisitorTracks::getVisitorId)
                                    .thenComparing(VisitorTracks::getFirstTimeSeen));
                            Map<Long, List<Integer>> vIdMap = visitorTracks.stream().filter(p -> p.getRegionId() > 0)
                                    .collect(Collectors.groupingBy(
                                            VisitorTracks::getVisitorId,
                                            Collectors.mapping(VisitorTracks::getRegionId, Collectors.toList())));

                            int i = 0;
                            for (Map.Entry<Long, List<Integer>> entry : vIdMap.entrySet()) {
                                if (Collections.indexOfSubList(entry.getValue(), locIdList) > -1) {
                                    i++;
                                }
                            }

                            response.put("count", i);
                            return new ResponseEntity<>(response, HttpStatus.OK);
                        }

                    }


                } else {
                    logger.info("retrieving result from internal server");
                    //get response from internal archive server
                    RestTemplateClient restTemplate = new RestTemplateClient();
                    String domain = dd.get().getServer().getDomain();

                    if (domain != null) {
                        String uri = domain + "/internal/mobility/visitorDetection";

                        String url = UriComponentsBuilder
                                .fromUriString(uri)
                                .queryParam("datasetDefinitionId", datasetDef.getId())
                                .queryParam("datasetId", datasetId)
                                .queryParam("locationIds", Arrays.toString(locationIds))
                                .build().toUriString();
                        logger.info(url);
                        try {
                            JsonNode node = restTemplate.restTemplate().exchange(url,
                                    HttpMethod.GET, null, JsonNode.class).getBody();
                            if (node != null) {
                                JsonFactory jsonFactory = new JsonFactory();
                                ObjectMapper objectMapper = new ObjectMapper(jsonFactory);
                                JsonNode jsonNode = objectMapper.readValue(node.toString(), JsonNode.class);
                                if (jsonNode == null) {
                                    errorMessage="An error occurred while processing your request";
                                    logger.error(errorMessage);
                                    response.put("error", errorMessage);
                                    return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                                }else{
                                    response.put("count", jsonNode.asInt());
                                    return new ResponseEntity<>(response, HttpStatus.OK);
                                }
                            }
                        } catch (Exception ex) {
                            errorMessage = ex.getMessage();
                            logger.error(errorMessage);
                            response.put("error", errorMessage);
                            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                        }
                    }
                }
            }
        }
        errorMessage = "An error occurred while processing your request";
        logger.error(errorMessage);
        response.put("error", errorMessage);
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static VisitorTracks createVisitorTrack(String[] metadata) {
        int siteId, regionId, populationType, globalId;
        long visitorId;
        String beginTime, endTime;
        //site_id,region_id,visitor_id,device_id,device_type,population_type,global_id,first_time_seen,last_time_seen
        try {

            siteId = Integer.parseInt(metadata[0]);
            regionId = Integer.parseInt(metadata[1]);
            visitorId = Long.parseLong(metadata[2]);
            populationType = Integer.parseInt(metadata[5]);
            globalId = Integer.parseInt(metadata[6]);

            beginTime = metadata[7];
            endTime = metadata[8];

            return new VisitorTracks(siteId, regionId, visitorId, populationType, globalId, beginTime, endTime);

        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        }
    }

    private static VisitorTracksNew createVisitorTrackNew(String[] metadata) {
        int datasetId, locationId;
        String startTime, endTime, distance, anonymizedUniqueId;
        //dataset_id,location_id,anonymized_unique_id,start_time,end_time,distance
        try {

            datasetId = Integer.parseInt(metadata[0]);
            locationId = Integer.parseInt(metadata[1]);
            anonymizedUniqueId = metadata[2];
            startTime = metadata[3];
            endTime = metadata[4];
            distance = metadata[5];

            return new VisitorTracksNew(datasetId, locationId, anonymizedUniqueId, startTime, endTime, distance);

        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        }
    }

    //TODO for client use
    @PostMapping("/mobility/anonymizationJob")
    public ResponseEntity<Resource> anonymizeExternalAPI(@RequestPart DatasetDefinitionDTO dataset,
                                                         @RequestPart MultipartFile file) {
        String errorMessage;

        if (dataset.getName() == null) {
            errorMessage = "Name is required";
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, errorMessage);
        }

        if (dataset.getK() == null || (dataset.getK() < 0 || dataset.getK() > 100)) {
            errorMessage = "k must be a number between 0 - 100. You provided " + dataset.getK();
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, errorMessage);
        }

        if (!Objects.requireNonNull(file.getOriginalFilename()).endsWith(".csv")) {
            errorMessage = "Please upload a csv file. You provided " + file.getOriginalFilename();
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, errorMessage);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            errorMessage = "Exceeded max file size " + MAX_FILE_SIZE;
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, errorMessage);
        }


        DatasetDefinition ds = datasetDefinitionBusiness.save(dataset);

        File fOrg;
        String path = "/data/mobility/" + ds.getId();
        try {
            fOrg = new File(path);
            fOrg.setReadable(true, false);
            fOrg.setWritable(true, false);
            fOrg.mkdirs();
            Files.setPosixFilePermissions(Path.of("/data/mobility/" + ds.getId()), PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (IOException e) {
            errorMessage = e.getMessage();
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
        }

        Path uploadPath = Paths.get(path);
        String fileName;

        BufferedReader br;
        String csvDate = null;
        String line;
        try {
            InputStream is = file.getInputStream();
            br = new BufferedReader(new InputStreamReader(is));
            line = br.readLine();
            String[] nextRecord;
            if (line != null) {
                nextRecord = line.split(",");
                int dateIndex = Arrays.asList(nextRecord).indexOf(START_TIME);
                if (dateIndex > 0 && (line = br.readLine()) != null) {
                    csvDate = line.split(",")[dateIndex];
                    csvDate = csvDate.split(" ")[0];
                    if (!GenericValidator.isDate(csvDate, DATE_FORMAT, true)) {
                        errorMessage = "start_time must be yyyy-MM-dd HH:mm:ss format";
                        logger.error(errorMessage);
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
                    }
                }
            }
            br.close();
        } catch (IOException e) {
            errorMessage = e.getMessage();
            logger.error(errorMessage);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
        }


        if (csvDate != null) {
            UUID uuid = UUID.randomUUID();
            fileName = "dataset-" + uuid + "-" + csvDate + ".csv";

            //process anonymization


            long dataPoints;
            try {
                FileUploadUtil.saveFile(fileName, file, uploadPath);
            } catch (IOException e) {
                errorMessage = e.getMessage();
                logger.error(errorMessage);
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
            }

            File fi = new File(path + "/" + fileName);
            fi.setReadable(true, false);
            fi.setWritable(true, false);

            Path filePath = Paths.get(uploadPath + "/" + fileName);
            try (Stream<String> stream = Files.lines(filePath)) {
                dataPoints = stream.count() - 1;
                Dataset d = new Dataset();
                d.setId(uuid);
                d.setDatasetDefinition(ds);
                d.setStartDate(Date.valueOf(csvDate));
                d.setEndDate(Date.valueOf(csvDate));
                d.setResolution(dataset.getResolution());
                d.setK(dataset.getK());
                d.setDataPoints(dataPoints);

                datasetBusiness.save(d);

                br = new BufferedReader(
                        new InputStreamReader(new FileSystemResource("/data/mobility/" + ds.getId() + "/" + fileName).getInputStream()));

                String lineDownload;
                StringBuffer inputBuffer = new StringBuffer();
                HttpHeaders responseHeaders = new HttpHeaders();

                while ((lineDownload = br.readLine()) != null) {
                    inputBuffer.append(lineDownload);
                    inputBuffer.append('\n');
                }
                br.close();

                String inputStr = inputBuffer.toString();

                ContentDisposition contentDisposition = ContentDisposition.builder("inline")
                        .filename(fi.getName())
                        .build();
                responseHeaders.setContentDisposition(contentDisposition);
                InputStream is = new ByteArrayInputStream(inputStr.getBytes(StandardCharsets.UTF_8));
                InputStreamResource resource = new InputStreamResource(is);
                return ResponseEntity.ok()
                        .headers(responseHeaders)
                        .contentLength(is.available())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);

            } catch (IOException e) {
                errorMessage = e.getMessage();
                logger.error(errorMessage);
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
            }
        }

        errorMessage = "An error occurred while processing your request";
        logger.error(errorMessage);
        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
    }

    public static String getRandomNumberString() {
        Random rnd = new Random();
        int number = rnd.nextInt(999999);
        return String.format("%06d", number);
    }
}