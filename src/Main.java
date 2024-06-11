import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class Main {

	// URL for the Gemini API
	private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent";
	// Path to the configuration file -> Must add your Key in this file
	private static final String CONFIG_FILE = "src/resources/config.properties";
	
	// Maximum length for conversation history to prevent it from becoming too large
    private static final int MAX_HISTORY_LENGTH = 100; // Limit conversation history size

    // Atomic reference to store the API key in a thread-safe manner
	private static final AtomicReference<String> apiKey = new AtomicReference<>();
	
	 // Gson instance for JSON serialization and deserialization
	private static final Gson gson = new Gson();
	
	// StringBuilder to keep track of the conversation history
	private static final StringBuilder conversationHistory = new StringBuilder();

	// Method to load google key API from the config file
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
				sendRequest(ask); // Send request to the Gemini API for non-quit input
			}
		}
		scan.close();
		System.exit(0);
	}

	private static void sendRequest(String prompt) throws IOException, InterruptedException {
		var client = HttpClient.newHttpClient();

		// Build prompt with conversation history
		String fullPrompt = buildPromptWithHistory(prompt);

		 // Create an HTTP request with the prompt
		var request = HttpRequest.newBuilder()
				.uri(URI.create(GEMINI_URL + "?alt=sse&key=" + apiKey.get()))
				.POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(fullPrompt)))
				.headers("Content-Type", "application/json")
				.build();

		// Send the request asynchronously and process the response
		var task = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(t -> {
					try {
						processResponse(t); // Process the response once received
					} catch (Exception e) {
						System.out.println("Error: " + e.getMessage());	
					}
				});

		task.join();
	}

	// Method to build the prompt including conversation history
	// I added this to try keep context in the chat, but it is not 
	//working very well, because it accumulate answers and 
	//answer all in every request
	private static String buildPromptWithHistory(String prompt) {
		 // Limit conversation history size
        if (conversationHistory.length() > MAX_HISTORY_LENGTH) {
            conversationHistory.delete(0, conversationHistory.length() - MAX_HISTORY_LENGTH);
        }
        conversationHistory.append(prompt).append(" ");
        return conversationHistory.toString();
	}

	 // Method to build the JSON body for the HTTP request
	private static String buildRequestBody(String prompt) {
		GeminiRequest request = new GeminiRequest();
		Content content = new Content();
		Part part = new Part();
		part.text = prompt; // Set the prompt text
		content.parts = List.of(part);
		request.contents = new Content[] {content};
		return gson.toJson(request);
	}

	 // Method to process the HTTP response from the Gemini API
	private static void processResponse(HttpResponse<String> response) throws IOException {
		Executors.newSingleThreadExecutor().submit(() -> {

			var responseStr = new StringBuffer();
			try (var scanner = new Scanner(response.body())) {
				while (scanner.hasNextLine()) {
					String line = scanner.nextLine();
					if (line.length() == 0) { continue; }  // Skip empty lines
					var jsn = new StringBuffer(line).delete(0, 5).toString();
					ResponseData responseData = gson.fromJson(jsn, new TypeToken<ResponseData>() {}.getType());
					 // Extract the response text and append it to the response string
					responseStr.append(responseData.candidates().get(0).content.parts.get(0).text);
					responseStr.append(" ");
				}
			}

	//		conversationHistory += responseStr.toString(); // Update conversation history
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

