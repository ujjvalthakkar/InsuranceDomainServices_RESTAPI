package com.neu.medapp.main;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.neu.medapp.encryption.GenerateToken;
import com.neu.medapp.utils.RedisConnection;

import redis.clients.jedis.Jedis;

@SpringBootApplication
public class Application {

	public static void main(String[] args) throws ParseException {
		SpringApplication.run(Application.class, args);

		String result = "";

		String planSchema = new Application().getFileWithUtil("Schema/Schema.json");

		JSONParser parser = new JSONParser();
		Object jsonObj = parser.parse(planSchema);
		JSONObject jsonObject = (JSONObject) jsonObj;

		Jedis jedis = RedisConnection.getConnection();
		jedis.set("planSchema", planSchema);

		String encryptData1 = new Application().getFileWithUtil("Schema/Encrypt.json");
		String encryptData2 = new Application().getFileWithUtil("Schema/Encrypt1.json");
		String encryptData3 = new Application().getFileWithUtil("Schema/Encrypt2.json");
		String s = null;
		try {
			s = GenerateToken.encrypt(encryptData1);
			GenerateToken.encrypt(encryptData2);
			GenerateToken.encrypt(encryptData3);
			GenerateToken.decrypt(s);
		} catch (Exception e) {
			System.out.println("Encryption Error:" + e.getMessage());
		}

		JSONObject mainProperties = (JSONObject) jsonObject.get("properties");
		postSchema(mainProperties, jedis);
	}

	public static void postSchema(JSONObject mainProperties, Jedis jedis) {
		Set keys = mainProperties.keySet();
		Iterator itr = keys.iterator();

		while (itr.hasNext()) {
			String key = (String) itr.next();
			JSONObject objprop = (JSONObject) mainProperties.get(key);
			if (objprop.get("type").toString().equalsIgnoreCase("object")) {

				// String type =
				// ((JSONObject)((JSONObject)objprop.get("properties")).get("_type")).get("description").toString();
				jedis.set("planschema_" + key, mainProperties.get(key).toString());

				JSONObject prop = (JSONObject) objprop.get("properties");
				Set keys1 = prop.keySet();
				Iterator itr1 = keys1.iterator();

				while (itr1.hasNext()) {
					String key1 = (String) itr1.next();
					JSONObject objprop1 = (JSONObject) prop.get(key1);
					if (objprop1.get("type").toString().equalsIgnoreCase("object")
							|| objprop1.get("type").toString().equalsIgnoreCase("array")) {
						postSchema(prop, jedis);
					}
				}

			} else if (objprop.get("type").toString().equalsIgnoreCase("array")) {
				jedis.set("planschema_" + key, mainProperties.get(key).toString());
				JSONObject o = (JSONObject) mainProperties.get(key);
				JSONArray items = (JSONArray) o.get("items");
				for (int i = 0; i < items.size(); i++) {
					JSONObject properties = (JSONObject) items.get(i);
					postSchema((JSONObject) properties.get("properties"), jedis);
				}

			}
		}
	}

	public String getFileWithUtil(String fileName) {

		String result = "";

		ClassLoader classLoader = getClass().getClassLoader();
		try {
			result = IOUtils.toString(classLoader.getResourceAsStream(fileName));
		} catch (IOException e) {
			e.printStackTrace();
		}

		return result;
	}
}
