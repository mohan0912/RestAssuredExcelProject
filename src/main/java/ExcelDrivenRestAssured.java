import io.restassured.RestAssured;
import io.restassured.config.SSLConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

public class ExcelDrivenRestAssured {

    static final String EXCEL_PATH = "input.xlsx";

    public static void main(String[] args) throws Exception {
        boolean RUN_MODE = false; // Default mode
        if (args.length > 0) {
            RUN_MODE = args[0].equalsIgnoreCase("run");
        }

        FileInputStream fis = new FileInputStream(EXCEL_PATH);
        Workbook workbook = new XSSFWorkbook(fis);
        Sheet sheet = workbook.getSheetAt(0);
        Row headerRow = sheet.getRow(0);

        Map<String, Integer> colIndexMap = getColumnIndexMap(headerRow);
        List<String> requiredCols = Arrays.asList("RunFlag", "Method", "URL", "Headers");

        for (String col : requiredCols) {
            if (!colIndexMap.containsKey(col)) {
                System.err.println("Missing required column: " + col);
                workbook.close();
                fis.close();
                return;
            }
        }

        for (String col : Arrays.asList("ResponseCode", "ResponseBody")) {
            if (!colIndexMap.containsKey(col)) {
                int lastCol = headerRow.getLastCellNum();
                Cell newCell = headerRow.createCell(lastCol);
                newCell.setCellValue(col);
                colIndexMap.put(col, Integer.valueOf(lastCol));
            }
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String outputFilePath = "api_output_log_" + timestamp + ".txt";
        FileWriter writer = new FileWriter(outputFilePath);
        JSONArray postmanRequests = new JSONArray();
        Map<String, JSONArray> folders = new HashMap<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String runFlag = getCellValue(row, colIndexMap.get("RunFlag"));
            if (!"Y".equalsIgnoreCase(runFlag)) continue;

            String scenario = getCellValue(row, colIndexMap.getOrDefault("ScenarioName", Integer.valueOf(-1)));
            String method = getCellValue(row, colIndexMap.get("Method"));
            String url = getCellValue(row, colIndexMap.get("URL"));
            String pathParams = getCellValue(row, colIndexMap.getOrDefault("PathParams", Integer.valueOf(-1)));
            url = replacePathParams(url, pathParams);

            Map<String, String> rowData = extractRowData(row, colIndexMap);


            String certPath = rowData.getOrDefault("CertPath", "");
            String certPassword = rowData.getOrDefault("CertPassword", "");
            if (!certPath.isEmpty() && !certPassword.isEmpty()) {
                RestAssured.config = RestAssured.config().sslConfig(SSLConfig.sslConfig().keyStore(certPath, certPassword));
            }
            RequestSpecification req = given();
            applyAuth(req, rowData);
            Map<String, String> headersMap = addHeaders(req, rowData.get("Headers"));
            addQueryParams(req, rowData.get("QueryParams"));
            addFormData(req, rowData.get("FormData"));
            addCookies(req, rowData.get("Cookies"));
            String filePath = rowData.getOrDefault("FilePath", "");
            String fileField = rowData.getOrDefault("FileFieldName", "file");
            if (!filePath.isEmpty()) {
                File file = new File(filePath);
                if (file.exists()) {
                    req.multiPart(fileField, file);
                }
            }

            String requestBody = "";
            if (!rowData.getOrDefault("Body", "").isEmpty()) {
                requestBody = rowData.get("Body");
                String contentType = null;
                if (requestBody.trim().startsWith("<")) {
                    contentType = "application/xml";
                } else if (requestBody.trim().startsWith("{")) {
                    contentType = "application/json";
                }
                if (contentType != null) req.contentType(contentType);
                req.body(requestBody);
            }

            long start = System.currentTimeMillis();
            Response res;
            switch (method.toUpperCase()) {
                case "GET":
                    res = req.get(url);
                    break;
                case "POST":
                    res = req.post(url);
                    break;
                case "PUT":
                    res = req.put(url);
                    break;
                case "PATCH":
                    res = req.patch(url);
                    break;
                case "DELETE":
                    res = req.delete(url);
                    break;
                default:
                    throw new RuntimeException("Unsupported method: " + method);
            }
            long timeTaken = System.currentTimeMillis() - start;
            int statusCode = res.getStatusCode();
            String responseBody = res.asPrettyString();
            responseBody = responseBody.length() > 32000 ? responseBody.substring(0, 32000) + "...(truncated)" : responseBody;

            writer.write("SCENARIO: " + scenario + "\n");
            writer.write("METHOD: " + method + "\n");
            writer.write("REQUEST HEADERS:\n");
            for (Map.Entry<String, String> entry : headersMap.entrySet()) {
                writer.write(entry.getKey() + ": " + entry.getValue() + "\n");
            }
            writer.write("QUERY PARAMS:\n" + rowData.getOrDefault("QueryParams", "") + "\n");
            writer.write("FORM DATA:\n" + rowData.getOrDefault("FormData", "") + "\n");
            writer.write("COOKIES:\n" + rowData.getOrDefault("Cookies", "") + "\n");
            writer.write("REQUEST BODY:\n" + requestBody + "\n");
            writer.write("URL: " + url + "\n");
            writer.write("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
            writer.write("RESPONSE CODE: " + statusCode + "\n");
            writer.write("TIME TAKEN: " + timeTaken + " ms\n");
            writer.write("RESPONSE HEADERS:\n");
            res.getHeaders().forEach(header -> {
                try {
                    writer.write(header.getName() + ": " + header.getValue() + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            writer.write("RESPONSE BODY:\n" + responseBody + "\n");
            writer.write("=========================================================================\n");

            setCellValue(row, colIndexMap.get("ResponseCode"), String.valueOf(statusCode));
            setCellValue(row, colIndexMap.get("ResponseBody"), responseBody);

            Thread.sleep(2000); // 2-second pause after each request
            if (!RUN_MODE) {
                JSONObject request = new JSONObject();
                request.put("method", method.toUpperCase());

                String host = url.replaceFirst("https?://", "").split("/")[0];
                String[] pathParts = url.replaceFirst("https?://[^/]+/?", "").split("/");

                JSONObject urlObject = new JSONObject()
                        .put("raw", url)
                        .put("host", new JSONArray(List.of(host.split("\\."))))
                        .put("path", new JSONArray(List.of(pathParts)));

                request.put("url", urlObject);

                // In Postman export logic
                if (!rowData.getOrDefault("Headers", "").isEmpty()) {
                    JSONArray headerArray = new JSONArray();
                    for (String h : rowData.get("Headers").split(",")) {
                        String[] kv = h.split("=", 2);
                        if (kv.length == 2) {
                            headerArray.put(new JSONObject()
                                    .put("key", kv[0].trim())
                                    .put("value", kv[1].trim()));
                        }
                    }
                    request.put("header", headerArray);
                }

                if (!rowData.getOrDefault("Cookies", "").isEmpty()) {
                    JSONArray cookieArray = new JSONArray();
                    for (String c : rowData.get("Cookies").split(",")) {
                        String[] kv = c.split("=", 2);
                        if (kv.length == 2) {
                            cookieArray.put(new JSONObject()
                                    .put("key", kv[0].trim())
                                    .put("value", kv[1].trim()));
                        }
                    }
                    request.put("cookie", cookieArray);
                }

                if (!rowData.getOrDefault("FilePath", "").isEmpty() || !rowData.getOrDefault("FormData", "").isEmpty()) {
                    JSONObject body = new JSONObject();
                    JSONArray formdata = new JSONArray();

                    // Add form-data fields
                    String formStr = rowData.getOrDefault("FormData", "");
                    if (!formStr.isEmpty()) {
                        for (String f : formStr.split(",")) {
                            String[] kv = f.split("=", 2);
                            if (kv.length == 2) {
                                String key = kv[0].trim();
                                String value = kv[1].trim();
                                if (key.startsWith("mp_")) {
                                    formdata.put(new JSONObject()
                                            .put("key", key.substring(3))
                                            .put("value", value)
                                            .put("type", "text"));
                                } else if (key.startsWith("file_")) {
                                    formdata.put(new JSONObject()
                                            .put("key", key.substring(5))
                                            .put("type", "file")
                                            .put("src", value));
                                } else {
                                    formdata.put(new JSONObject()
                                            .put("key", key)
                                            .put("value", value)
                                            .put("type", "text"));
                                }
                            }
                        }
                    }

                    // Add default file field if specified
                    String filePathPostman = rowData.getOrDefault("FilePath", "");
                    if (!filePathPostman.isEmpty()) {
                        String field = rowData.getOrDefault("FileFieldName", "file");
                        formdata.put(new JSONObject()
                                .put("key", field)
                                .put("type", "file")
                                .put("src", filePathPostman));
                    }

                    body.put("mode", "formdata");
                    body.put("formdata", formdata);
                    request.put("body", body);
                } else if (!rowData.getOrDefault("Body", "").isEmpty()) {
                    JSONObject body = new JSONObject();
                    String rawBody = rowData.get("Body");
                    String mode = rawBody.trim().startsWith("<") ? "xml" : "raw";
                    body.put("mode", "raw");
                    body.put("raw", rawBody);
                    request.put("body", body);
                }
                JSONObject item = new JSONObject().put("name", scenario).put("request", request);
                folders.computeIfAbsent(method.toUpperCase(), k -> new JSONArray()).put(item);
            }
        }

        if (!RUN_MODE) {
            JSONArray items = new JSONArray();
            folders.forEach((k, v) -> items.put(new JSONObject().put("name", k).put("item", v)));
            JSONObject collection = new JSONObject();
            collection.put("info", new JSONObject().put("name", "GeneratedCollection").put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"));
            collection.put("item", items);
            try (FileWriter pmWriter = new FileWriter("generated_postman_collection.json")) {
                pmWriter.write(collection.toString(2));
            }
            System.out.println("Postman collection saved to generated_postman_collection.json");
            System.out.println("Execution done. Logs saved to " + outputFilePath);
        } else {
            System.out.println("Execution done. Logs saved to " + outputFilePath);
        }

        fis.close();
        FileOutputStream fos = new FileOutputStream(EXCEL_PATH);
        workbook.write(fos);
        fos.close();
        workbook.close();
        writer.close();
    }

    static Map<String, Integer> getColumnIndexMap(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (Cell cell : headerRow) {
            map.put(cell.getStringCellValue(), Integer.valueOf(cell.getColumnIndex()));
        }
        return map;
    }

    static String getCellValue(Row row, int colIndex) {
        if (colIndex == -1) return "";
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    static void setCellValue(Row row, int colIndex, String value) {
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        cell.setCellValue(value);
    }

    static Map<String, String> extractRowData(Row row, Map<String, Integer> colMap) {
        Map<String, String> data = new HashMap<>();
        for (Map.Entry<String, Integer> entry : colMap.entrySet()) {
            data.put(entry.getKey(), getCellValue(row, entry.getValue()));
        }
        return data;
    }

    static String replacePathParams(String url, String paramStr) {
        if (paramStr == null || paramStr.isEmpty()) return url;
        for (String part : paramStr.split(",")) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
                url = url.replace("{" + kv[0].trim() + "}", kv[1].trim());
            }
        }
        return url;
    }

    static Map<String, String> addHeaders(RequestSpecification req, String headerStr) {
        Map<String, String> headers = new HashMap<>();
        if (headerStr == null || headerStr.isEmpty()) return headers;
        for (String h : headerStr.split(",")) {
            String[] kv = h.split("=");
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                req.header(key, val);
                headers.put(key, val);
            }
        }
        return headers;
    }

    static void addQueryParams(RequestSpecification req, String queryStr) {
        if (queryStr == null || queryStr.isEmpty()) return;
        for (String q : queryStr.split(",")) {
            String[] kv = q.split("=");
            if (kv.length == 2) req.queryParam(kv[0].trim(), kv[1].trim());
        }
    }

    static void addCookies(RequestSpecification req, String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) return;
        for (String c : cookieStr.split(",")) {
            String[] kv = c.split("=");
            if (kv.length == 2) req.cookie(kv[0].trim(), kv[1].trim());
        }
    }

    static void applyAuth(RequestSpecification req, Map<String, String> row) {
        String authType = row.getOrDefault("AuthType", "").trim().toLowerCase();
        switch (authType) {
            case "basic":
                req.auth().preemptive().basic(row.getOrDefault("Username", ""), row.getOrDefault("Password", ""));
                break;
            case "bearer":
            case "oauth2":
                req.header("Authorization", "Bearer " + row.getOrDefault("Token", ""));
                break;
            case "none":
            case "":
                break;
            default:
                throw new RuntimeException("Unsupported AuthType: " + authType);
        }
    }

    static void addFormData(RequestSpecification req, String formStr) {
        if (formStr == null || formStr.isEmpty()) return;
        for (String f : formStr.split(",")) {
            String[] kv = f.split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                if (key.startsWith("mp_")) {
                    req.multiPart(key.substring(3), value); // multipart text field
                } else if (key.startsWith("file_")) {
                    File file = new File(value);
                    if (file.exists()) {
                        req.multiPart(key.substring(5), file); // multipart file
                    }
                } else {
                    req.formParam(key, value); // regular form param
                }
            }
        }
    }

}
