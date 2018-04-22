package com.neu.medapp.main;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.neu.medapp.encryption.*;
import com.neu.medapp.utils.RedisConnection;
import com.neu.medapp.utils.ValidateJSON;

import redis.clients.jedis.Jedis;

@RestController
public class PlansController {

	public static final String AUTHENTICATION_HEADER = "Authorization";
	Calendar cal = Calendar.getInstance();
	DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	public static final String WORK_QUEUE = "WORK_QUEUE";
	public static final String WORK_QUEUE1 = "WORK_QUEUE1";

	public static Settings settings = Settings.builder().put("cluster.name", "elasticsearch").build();

	public static TransportClient transportClient() throws UnknownHostException {
		return new PreBuiltTransportClient(settings)
				.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9300));
	}

	CreateIndexRequestBuilder request = null;

	public static CreateIndexRequestBuilder requestt = null;
	public static IndexResponse responseIndex = null;

	@RequestMapping(value = "/Schema", method = RequestMethod.GET, headers = "Accept=application/json")
	public JSONObject getPlanSchema() {

		Jedis jedis = RedisConnection.getConnection();
		String planSchema = jedis.get("planSchema");
		JSONParser parser = new JSONParser();
		JSONObject obj = null;
		try {
			obj = (JSONObject) parser.parse(planSchema);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return obj;
	}

	@RequestMapping(value = "/plan/{planId}", method = RequestMethod.GET, headers = "Accept=application/json")
	public Object getPlan(@PathVariable String planId, HttpServletRequest request, HttpServletResponse response)
			throws Exception {

		String authCode[] = request.getHeader(AUTHENTICATION_HEADER).split(" ");
		String access = validateToken(authCode[1], "GET");

		if (access.contains(",")) {
			response.setStatus(401);
			return "You are not authorized to make a GET Request";
		} else if (access.contains("false")) {
			response.setStatus(403);
			return "Invalid Token!!!";
		}

		Jedis jedis = RedisConnection.getConnection();
		String id = "plan__" + planId + "__*";
		Set<String> plankey = jedis.keys(id);
		//System.out.println("====>>>>>" + plankey);
		if (plankey.isEmpty()) {
			JSONObject errorMessage = new JSONObject();
			errorMessage.put("Input error", "Plan does not exist");
			response.setStatus(404);
			return errorMessage;
		}
		for (String s : plankey) {
			if (s.indexOf("__etag") == -1) {
				//System.out.println("====>>>>>" + s);
				if (!jedis.exists(s)) {
					JSONObject errorMessage = new JSONObject();
					errorMessage.put("Input error", "Plan does not exist");
					response.setStatus(404);
					return errorMessage;
				}
				String etagId = "plan__" + planId + "__etag";
				response.setHeader("eTag", jedis.get(etagId));
				System.out.println(jedis.get(s));
				return jedis.get(s);
			}
		}

		return "";
	}

	@RequestMapping(value = "/plans/{planId}", method = RequestMethod.GET, headers = "Accept=application/json")
	public Object getPlanObject(@PathVariable String planId, HttpServletRequest request, HttpServletResponse response)
			throws Exception {

		String authCode[] = request.getHeader(AUTHENTICATION_HEADER).split(" ");
		String access = validateToken(authCode[1], "GET");

		if (access.contains(",")) {
			response.setStatus(401);
			return "You are not authorized to make a GET Request";
		} else if (access.contains("false")) {
			response.setStatus(403);
			return "Invalid Token!!!";
		}

		Jedis jedis = RedisConnection.getConnection();
		String id = "*" + planId + "*";
		Set<String> plankey = jedis.keys(id);
		if (plankey.isEmpty()) {
			JSONObject errorMessage = new JSONObject();
			errorMessage.put("Input error", "Plan does not exist");
			response.setStatus(404);
			return errorMessage;
		}
		for (String s : plankey) {
			if (!jedis.exists(s)) {
				JSONObject errorMessage = new JSONObject();
				errorMessage.put("Input error", "Plan does not exist");
				response.setStatus(404);
				return errorMessage;
			}
			return jedis.get(s);
		}

		return "";
	}

	@RequestMapping(value = "/plan", method = RequestMethod.POST)
	@ResponseBody
	public String postPlan(@RequestBody JSONObject postedPlan, HttpServletRequest request, HttpServletResponse response)
			throws Exception {

		String authCode[] = request.getHeader(AUTHENTICATION_HEADER).split(" ");
		String access = validateToken(authCode[1], "POST");

		if (access.contains(",")) {
			response.setStatus(401);
			return "You are not authorized to make a POST Request";
		} else if (access.contains("false")) {
			response.setStatus(403);
			return "Invalid Token";
		}
		Boolean jsonValidity = ValidateJSON.isJSONValid(postedPlan.toJSONString());
		if (jsonValidity == false) {
			response.setStatus(400);
			return "JSON input invalid against schema";
		} else {
			Jedis jedis = RedisConnection.getConnection();
			String uuid = UUID.randomUUID().toString();
			Date date = new Date();
			String eTag = generateEtag(dateFormat.format(date));

			postSchema(postedPlan, jedis, uuid);
			String key = "plan__" + uuid + "__etag";
			response.setHeader("eTag", eTag);
			jedis.set(key, eTag);
			return "Plan Added! ID:" + uuid;

		}

	}

	@RequestMapping(value = "/plan/{planId}", method = RequestMethod.DELETE)
	@ResponseBody
	public String deletePlan(@RequestBody JSONObject postedPlan, @PathVariable String planId,
			HttpServletRequest request, HttpServletResponse response) throws Exception {

		String authCode[] = request.getHeader(AUTHENTICATION_HEADER).split(" ");
		String access = validateToken(authCode[1], "DELETE");

		if (access.contains(",")) {
			response.setStatus(401);
			return "You are not authorized to make a DELETE Request";
		} else if (access.contains("false")) {
			response.setStatus(403);
			return "Invalid Token";
		}

		Jedis jedis = RedisConnection.getConnection();
		String id = "*" + planId + "*";
		Set<String> plankey = jedis.keys(id);

		if (plankey.isEmpty()) {
			response.setStatus(404);
			return "Invalid Key. Key does not exist!!";
		}
		for (String s : plankey) {
			if (!jedis.exists(s)) {
				response.setStatus(404);
				return "Invalid Key. Key does not exist!!";
			}
			jedis.del(s);
		}

		return "Successful Deletion!!!";
	}

	@RequestMapping(value = "/plan/{planId}", method = RequestMethod.PUT)
	@ResponseBody
	public String putPlan(@RequestBody JSONObject postedPlan, @PathVariable String planId, HttpServletRequest request,
			HttpServletResponse response) throws Exception {

		String authCode[] = request.getHeader(AUTHENTICATION_HEADER).split(" ");
		String access = validateToken(authCode[1], "PUT");

		if (access.contains(",")) {
			response.setStatus(401);
			return "You are not authorized to make a PUT Request";
		} else if (access.contains("false")) {
			response.setStatus(403);
			return "Invalid Token";
		}

		Jedis jedis = RedisConnection.getConnection();
		String id = "*" + planId + "*";
		Set<String> plankey = jedis.keys(id);
		if (plankey.isEmpty()) {
			response.setStatus(404);
			return "Invalid Key. Key does not exist!!";
		}

		Boolean isJSONValid = ValidateJSON.isJSONValid(postedPlan.toJSONString());
		if (isJSONValid == false) {
			return "JSON input invalid against schema";
		} else {
			String etagId = "plan__" + planId + "__etag";
			String eTag = jedis.get(etagId);
			System.out.println(">>" + etagId + ">>" + eTag);
			String header = request.getHeader("If-Match");
			if (header == null) {
				response.setStatus(403);
				return "Include If-Match in the Header";
			} else if (!eTag.equals(header) || eTag == null) {
				response.setStatus(412);
				return "Plan may be changed since last update!!!";
			}

			postSchema(postedPlan, jedis, planId);

			Date date = new Date();
			String eTagNew = generateEtag(dateFormat.format(date));
			jedis.set(etagId, eTagNew);
			response.setHeader("eTag", eTagNew);

			return "Put Request Successful!!!" + "\nPlan Id:" + planId;

		}
	}

	public String validateToken(String token, String method) throws Exception {
		boolean flag = true;
		JSONArray roles = null;
		try {
			String json = GenerateToken.decrypt(token);

			JSONParser parser = new JSONParser();
			Object obj = parser.parse(json);
			JSONObject jsonObject = (JSONObject) obj;
			roles = (JSONArray) jsonObject.get("roles");
		} catch (Exception e) {
			flag = false;
			return "false";
		}
		if (flag == true) {
			if (roles != null && roles.contains(method)) {
				return "true";
			} else {
				return "false,denied";
			}
		} else {
			return "false";
		}
	}

	public String generateEtag(String valueTohash) throws NoSuchAlgorithmException {

		MessageDigest md = MessageDigest.getInstance("MD5");
		md.update(valueTohash.getBytes());
		byte byteData[] = md.digest();
		// convert the byte to hex format method 1
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < byteData.length; i++) {
			sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
		}
		return sb.toString();
	}

	public PlansController() {
		// TODO Auto-generated constructor stub
	}

	public static void postSchema(JSONObject mainProperties, Jedis jedis, String uuid)
			throws JsonParseException, JsonMappingException, IOException, ParseException, InterruptedException {

		String key = mainProperties.get("objectType").toString() + "__" + uuid + "__"
				+ mainProperties.get("objectId").toString();

		jedis.set(key, mainProperties.toJSONString());
		if (mainProperties.get("objectType").toString().equals("plan")) {
			fillQueue(key, jedis);
		}

		Map<String, Object> response = new ObjectMapper().readValue(mainProperties.toJSONString(), HashMap.class);

		for (Map.Entry<String, Object> entry : response.entrySet()) {

			if (entry.getValue().getClass().toString().equals("class java.util.LinkedHashMap")) {
				LinkedHashMap value = (LinkedHashMap) entry.getValue();
				JSONObject jsonobj = getJSONObject(value);
				postSchema(jsonobj, jedis, uuid);
			}
			if (entry.getValue().getClass().toString().equals("class java.util.ArrayList")) {
				ArrayList<LinkedHashMap> list = (ArrayList<LinkedHashMap>) entry.getValue();
				for (LinkedHashMap valuee : list) {
					JSONObject jsonobj = getJSONObject(valuee);
					postSchema(jsonobj, jedis, uuid);
				}
			}
		}
	}

	public static JSONObject getJSONObject(LinkedHashMap value) throws ParseException {
		JSONParser parser = new JSONParser();
		String s = new Gson().toJson(value, Map.class);
		JSONObject jsonobj = (JSONObject) parser.parse(s);
		return jsonobj;
	}

	public static void fillQueue(String planId, Jedis jedis) throws InterruptedException, UnknownHostException {
		jedis.lpush(WORK_QUEUE, planId);
		deQueue(jedis);
	}

	public static void deQueue(Jedis jedis) throws InterruptedException, UnknownHostException {
		TransportClient client = transportClient();
		boolean exists = client.admin().indices().prepareExists("plansindex").execute().actionGet().isExists();
		if (!exists) {			
			requestt = client.admin().indices().prepareCreate("plansindex");
			// Thread.sleep(1500);
		}

		String planDequeued = null;
		String object_type = null;
		String object_ID = null;

		try {

			planDequeued = jedis.brpoplpush(WORK_QUEUE, WORK_QUEUE1, 0);
			System.out.println("IndexKey====>" + planDequeued);
		} catch (Exception e) {
			System.out.println("Exception is " + e);

		} finally {

		}

		if (planDequeued != null) {
			String[] parts = planDequeued.split("__");
			object_type = parts[0];
			object_ID = parts[1];
			System.out.println("IndexValue====>" + jedis.get(planDequeued));
			IndexResponse responseIndexx = client.prepareIndex("plansindex", object_type, object_ID)
					.setSource(jedis.get(planDequeued)).execute().actionGet();
			System.out.println("responseIndexx -========>>>>" + responseIndexx.toString());
			jedis.del(WORK_QUEUE1);
		}

	}
}
