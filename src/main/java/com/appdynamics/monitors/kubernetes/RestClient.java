package com.appdynamics.monitors.kubernetes;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_CONTROLLER_API_USER;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_DASH_TEMPLATE_PATH;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RestClient {
    private static final String APPD_EVENT_CONTENT_TYPE = "application/vnd.appd.events+json;v=2";
	private static final Logger logger = LoggerFactory.getLogger(RestClient.class);


    public static String getRESTCredentials(Map<String, String> config){
        String creds = System.getenv("REST_API_CREDENTIALS");
        if (StringUtils.isNotEmpty(creds) == false){
            creds = config.get(CONFIG_CONTROLLER_API_USER);
        }
        return  creds;
    }

    private static HttpURLConnection openConnection(URL url,  Map<String, String> config) throws IOException {
        HttpURLConnection conn = null;
        String proxyHost = Utilities.getProxyHost(config);
        String proxyPort = Utilities.getProxyPort(config);
        if(StringUtils.isNotEmpty(proxyHost) && StringUtils.isNotEmpty(proxyPort)){
            Integer portNumber = Integer.parseInt(proxyPort);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, portNumber));
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", proxyPort);
            conn = (HttpURLConnection) url.openConnection(proxy);
        }
        else{
            conn = (HttpURLConnection) url.openConnection();
        }
        String proxyUser = Utilities.getProxyUser(config);
        String proxyPass = Utilities.getProxyPass(config);

        if(StringUtils.isNotEmpty(proxyUser) && StringUtils.isNotEmpty(proxyPass)) {
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication("proxyUser", proxyPass.toCharArray());
                }
            });
        }

        return conn;
    }

		public static JsonNode doGet(URL url, Map<String, String> config, String accountName, String apiKey, String requestBody, String method)
		{
			OkHttpClient client = new OkHttpClient().newBuilder()
					  .build();
					MediaType mediaType = MediaType.parse(APPD_EVENT_CONTENT_TYPE);
					RequestBody body = RequestBody.create(mediaType, requestBody);
					Request request = new Request.Builder()
					  .url(url)
					  .addHeader("X-Events-API-AccountName", accountName)
					  .addHeader("X-Events-API-Key", apiKey)
					  .addHeader("Accept", APPD_EVENT_CONTENT_TYPE)
					  .build();
					try {
						Response response = client.newCall(request).execute();
						ResponseBody responseBody = response.body();
						String responseBodyString = responseBody.string();
						logger.info("doGet response for URL: {}, message: {}  and responseCode is: {}", url, response.message(), response.code());
		
						ObjectMapper objectMapper = new ObjectMapper();
						JsonNode jsonNode = objectMapper.readTree(responseBodyString);
	    				responseBody.close(); // Close the response body stream

						return jsonNode;
					} catch (IOException e) {
						e.printStackTrace();
					}
					return null;
		}
    public static JsonNode doPost(URL url, Map<String, String> config, String accountName, String apiKey, String requestBody, String method){
    	
    	OkHttpClient client = new OkHttpClient().newBuilder()
    			  .build();
    			MediaType mediaType = MediaType.parse(APPD_EVENT_CONTENT_TYPE);
    			RequestBody body = RequestBody.create(mediaType, requestBody);
    			Request request = new Request.Builder()
    			  .url(url)
    			  .method("POST", body)
    			  .addHeader("Content-Type", APPD_EVENT_CONTENT_TYPE)
    			  .addHeader("X-Events-API-AccountName", accountName)
    			  .addHeader("X-Events-API-Key", apiKey)
    			  .addHeader("Accept", APPD_EVENT_CONTENT_TYPE)
    			  .build();
    			try {
    				Response response = client.newCall(request).execute();
    				ResponseBody responseBody = response.body();
    				String responseBodyString = responseBody.string();

    				logger.info("doPost response for URL: {}, message: {}  and responseCode is: {}", url, response.message(), response.code());

    				ObjectMapper objectMapper = new ObjectMapper();
    				JsonNode jsonNode = objectMapper.readTree(responseBodyString);

    				responseBody.close(); // Close the response body stream

    				return jsonNode;
    			} catch (IOException e) {
    				e.printStackTrace();
					logger.error("doPost  doPost Error while processing {} on URL {}. Reason {}", method, url, e.getMessage()+" :: "+e.getLocalizedMessage() );
		            
				}
				return null;
    }
    
    public static JsonNode doRequest(URL url, Map<String, String> config, String accountName, String apiKey, String requestBody, String method) {
        BufferedReader br = null;
        try {
            HttpURLConnection conn = openConnection(url, config);
			if (method=="POST") {
			    return	doPost(url, config, accountName, apiKey, requestBody, method);
			}else if(method=="GET") {
				return	doGet(url, config, accountName, apiKey, requestBody, method);
			}
            conn.setDoOutput(true);
            if (method.equals("PATCH")) {
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                conn.setRequestMethod("POST");
            } else {
                conn.setRequestMethod(method);
            }
            if (method.equals("POST") || method.equals("PATCH")) {
                conn.setRequestProperty("Content-Type", APPD_EVENT_CONTENT_TYPE);
            }
            conn.setRequestProperty("Accept", APPD_EVENT_CONTENT_TYPE);
            conn.setRequestProperty("X-Events-API-AccountName", accountName);
            conn.setRequestProperty("X-Events-API-Key", apiKey);
            if (method.equals("POST") || method.equals("PATCH")) {
                OutputStream output = conn.getOutputStream();
                output.write(requestBody.getBytes("UTF-8"));
            }
            br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            String response = "";
            for (String line; (line = br.readLine()) != null; response += line) ;
            conn.disconnect();
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(response);
        } catch (IOException e) {
            logger.error("Error while processing {} on URL {}. Reason {}", method, url, e.getMessage()+" :: "+e.getLocalizedMessage() );
            return null;
        }
        finally {
            try {
                if (br != null) {
                    br.close();
                }
            }
            catch (IOException ex){
                logger.error("Error while cleaning up streams and buffers in doRequest");
            }
        }
    }

    public static AppDRestAuth getAuthToken(Map<String, String> config) {
        AppDRestAuth authObj = new AppDRestAuth();
        HttpURLConnection conn = null;
        String path = Utilities.getControllerUrl(config) + "auth?action=login";
        URL url = Utilities.getUrl(path);
        String user = getRESTCredentials(config);
        if (user == null || user.isEmpty()){
            logger.error("Credentials for Controller API are not defined. Configure user credentials in config.yml (controllerAPIUser) or in REST_API_CREDENTIALS environmental variable");
            return null;
        }
        try {
            conn =  openConnection(url, config);
            conn.setRequestMethod("GET");

            byte[] message = (user).getBytes("UTF-8");
            String encoded = Base64.getEncoder().encodeToString(message);
            conn.setRequestProperty("Authorization", "Basic " + encoded);

            int responseCode = conn.getResponseCode();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            if (responseCode == 200) {
                String sessionID = "";
                for (Map.Entry<String, List<String>> headers : conn.getHeaderFields().entrySet()) {
                    if (headers != null) {
                        if (headers.getKey() != null && headers.getKey().toLowerCase().equals("set-cookie")) {
                            for (String cookie : headers.getValue()) {
                                if (cookie.contains("X-CSRF-TOKEN")) {
                                    Pattern pattern = Pattern.compile("=(.*?);");
                                    Matcher matcher = pattern.matcher(cookie);
                                    if (matcher.find()) {
                                        authObj.setToken(matcher.group(1));
                                    }
                                }

                                if (cookie.contains("JSESSIONID")) {
                                    Pattern pattern = Pattern.compile("=(.*?);");
                                    Matcher matcher = pattern.matcher(cookie);
                                    if (matcher.find()) {
                                        sessionID = matcher.group(1);
                                    }
                                }

                            }
                        }
                    }
                }
                if (sessionID.length() > 0) {
                    authObj.setCookie(String.format("X-CSRF-TOKEN=%s; JSESSIONID=%s", authObj.getToken(), sessionID));
                }
            }
            else{
                logger.error("Authentication with Controller API failed. Check Controller API user credentials in config.yml or in REST_API_CREDENTIALS environmental variable");
                return null;
            }
        } catch (Exception ex) {
            logger.error("Issues when getting the auth token for restui calls", ex);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return authObj;
    }

    public static JsonNode callControllerAPI(String urlPath, Map<String, String> config, String requestBody, String method) {
        AppDRestAuth authObj = getAuthToken(config);
        if (authObj == null){
            return null;
        }
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            String path = Utilities.getControllerUrl(config) + urlPath;
            URL url = Utilities.getUrl(path);
            conn = openConnection(url, config);
            conn.setDoOutput(true);
            if (method.equals("PATCH")) {
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                conn.setRequestMethod("POST");
            } else {
                conn.setRequestMethod(method);
            }
            if (method.equals("POST") || method.equals("PATCH")) {
                conn.setRequestProperty("Content-Type", "application/json");
            }
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-CSRF-TOKEN", authObj.getToken());
            conn.setRequestProperty("Cookie", authObj.getCookie());


            if (method.equals("POST") || method.equals("PATCH")) {
                OutputStream output = conn.getOutputStream();
                output.write(requestBody.getBytes("UTF-8"));
            }
            br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            String response = "";

            for (String line; (line = br.readLine()) != null; response += line) ;

            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(response);

        } catch (IOException e) {
            logger.error("Error while processing {} on URL {}. Reason {}, stacktrace: {}", method, urlPath, e.toString()+"---- cause- "+e.getCause(),e.getStackTrace());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            try {
                if (br != null) {
                    br.close();
                }
            }
            catch (IOException ex){
                logger.error("Error while cleaning up streams and buffers in callControllerAPI");
            }
        }
    }

    public static JsonNode createDashboard(Map<String, String> config, String filePath) {
        HttpURLConnection conn = null;
        String path = Utilities.getControllerUrl(config) + "CustomDashboardImportExportServlet";
        URL url = Utilities.getUrl(path);
        DataOutputStream request = null;
        FileInputStream inputStream = null;
        BufferedReader br = null;
        String user = getRESTCredentials(config);
        if (user == null || user.isEmpty()){
            logger.error("Credentials for Controller API are not defined. Configure user credentials in config.yml (controllerAPIUser) or in REST_API_CREDENTIALS environmental variable");
            return null;
        }
        File templateFile = new File(filePath);
        try {
            conn = openConnection(url, config);
            conn.setRequestMethod("GET");

            byte[] message = (user).getBytes("UTF-8");
            String encoded = Base64.getEncoder().encodeToString(message);
            conn.setRequestProperty("Authorization", "Basic " + encoded);

            String boundary = UUID.randomUUID().toString();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);

            request = new DataOutputStream(conn.getOutputStream());
            request.writeBytes("--" + boundary + "\r\n");
            request.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + templateFile.getName() + "\"\r\n\r\n");
            inputStream = new FileInputStream(templateFile);
            byte[] buffer = new byte[4096];
            int bytesRead = -1;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                request.write(buffer, 0, bytesRead);
            }
            request.writeBytes("\r\n");

            request.writeBytes("--" + boundary + "--\r\n");
            request.flush();


            int respCode = conn.getResponseCode();
            logger.info("Dashboard create response code = {}", respCode);

            br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            String response = "";

            for (String line; (line = br.readLine()) != null; response += line) ;


            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(response);
        }
        catch (Exception ex) {
            logger.error("Error while creating dashboard from template {} . Reason {}", config.get(CONFIG_DASH_TEMPLATE_PATH),  ex.toString());
            return null;
        }
        finally {
            try {
                if (br != null) {
                    br.close();
                }
                if (inputStream != null){
                    inputStream.close();
                }

                if (request != null){
                    request.close();
                }
            }
            catch (IOException ex){
                logger.error("Error while cleaning up streams and buffers in createDashboard");
            }
        }
    }
}
