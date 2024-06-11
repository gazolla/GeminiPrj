import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

public class Main {

	// URL for the Gemini API
	private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent";
	// Path to the configuration file -> Must add your Key in this file
	private static final String CONFIG_FILE = "src/resources/config.properties";
	// Maximum length for conversation history 
	private static final int MAX_HISTORY_LENGTH = 50; 
	// Atomic reference to store the API key in a thread-safe manner
	private static final AtomicReference<String> apiKey = new AtomicReference<>();
	// Gson instance for JSON 
	private static final Gson gson = new Gson();
	//List to keep track of the conversation context
	private static final ArrayList<String> context = new ArrayList<>();

	// Method to load google key API 
	private static void config() throws IOException{
		Properties prop = new Properties();
		try (var reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
			prop.load(reader);
			apiKey.set(prop.getProperty("key"));
			if (apiKey.get() == null) {
				throw new RuntimeException("Missing API key in config file");
			}
		}
	}

	public static void main(String[] args) throws IOException, InterruptedException { 
		config(); // Load google key API
		Scanner scan = new Scanner(System.in);
		String ask = "";
		System.out.print("you: ");
		while (!ask.equals("quit")) { // Loop until user types "quit"
			ask = scan.nextLine();
			if (ask.equals("quit")) {
				System.out.println("answer: bye");
			} else {
				sendRequest(ask); // Send request to the Gemini API 
			}
		}
		scan.close();
		System.exit(0);
	}

	private static void sendRequest(String prompt) throws IOException, InterruptedException {
		var client = HttpClient.newHttpClient();

		// Build prompt with conversation history
		String fullPrompt = buildPromptWithContext(prompt);

		// Create an HTTP request 
		var request = HttpRequest.newBuilder()
				.uri(URI.create(GEMINI_URL + "?alt=sse&key=" + apiKey.get()))
				.POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(fullPrompt)))
				.headers("Content-Type", "application/json; charset=UTF-8")
				.build();

		// Send the request asynchronously 
		var task = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(t -> {
					try {
						processResponse(t); // Process the response 
					} catch (Exception e) {
						System.out.println("Error: " + e.getMessage());	
					}
				});

		task.join();
	}

	// Method to build the prompt including conversation context	
	private static String buildPromptWithContext(String prompt) {
		// Limit conversation context size
		if (context.size() > MAX_HISTORY_LENGTH) {
			context.subList(0, MAX_HISTORY_LENGTH/2).clear();
		}
		context.add("Answer the question Briefly: "+prompt); 
		return context
				.stream()
                .collect(Collectors.joining("\n"));
		 
	}

	// Method to build the JSON body for the request
	private static String buildRequestBody(String prompt) {
		GeminiRequest request = new GeminiRequest();
		Content content = new Content();
		Part part = new Part();
		part.text = prompt; // Set the prompt text
		content.parts = List.of(part);
		request.contents = new Content[] {content};
		return gson.toJson(request);
	}

	// Method to process response 
	private static void processResponse(HttpResponse<String> response) throws IOException {
		if (response.statusCode() != 200) {
			System.out.println("Error: " + response.statusCode());
			return;
		}

		Executors.newSingleThreadExecutor().submit(() -> {
			var responseStr = new StringBuffer();
			String body = response.body();
			BufferedReader reader = new BufferedReader(new StringReader(body));
			String line;
			try {
				while ((line = reader.readLine()) != null) {
					if (line.isEmpty()) { continue; }  // Skip empty lines
					var jsn = line.substring(5);
					ResponseData responseData = gson.fromJson(jsn, new TypeToken<ResponseData>() {}.getType());
					// Extract the response text and append it to the response string
					responseStr.append(responseData.candidates().get(0).content.parts.get(0).text);
					responseStr.append(" ");
				}
			} catch (JsonSyntaxException | IOException e) {
				e.printStackTrace();
			}
			
			context.remove(context.size() - 1);
		    buildPromptWithContext(responseStr.toString());
		    
			System.out.println("answer: " + responseStr);
			System.out.print("you: ");
		});
	}
}


// classes to map json from request and response
record ResponseData(
		List<Candidate> candidates,
		UsageMetadata usageMetadata
		) {}

class Candidate {
	public Content content;
	public String finishReason;
	public int index;
}

class GeminiRequest {
	public Content[] contents;
}

class Content {
	public List<Part> parts;
	public String role;
}

class Part {
	public String text;
}

class UsageMetadata {
	public int promptTokenCount;
	public int candidatesTokenCount;
	public int totalTokenCount;
}

