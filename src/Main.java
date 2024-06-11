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

enum HttpStatus {
	OK(200, "OK"),
	BAD_REQUEST(400, "Bad Request"),
	UNAUTHORIZED(401, "Unauthorized"),
	FORBIDDEN(403, "Forbidden"),
	NOT_FOUND(404, "Not Found"),
	INTERNAL_SERVER_ERROR(500, "Internal Server Error");

	private final int code;
	private final String message;

	HttpStatus(int code, String message) {
		this.code = code;
		this.message = message;
	}

	public int getCode() { return code;	}
	public String getMessage() { return message; }
	
	public static String getMessageFromCode(int code) {
        for (HttpStatus status : HttpStatus.values()) {
            if (status.getCode() == code) {
                return status.getMessage();
            }
        }
        return "Unknown Status"; // Return default message if code is not found
    }
}

public class Main {

	/*****************************************************************
	 * URL for the Gemini API 
	 * 
	 * https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent
	 * 
	 * gemini-1.5-flash: This is a powerful, large language model (LLM) that's 
	 * particularly well-suited for tasks involving factual accuracy and 
	 * complex reasoning. It's known for its high-quality responses and its 
	 * ability to handle a wide range of prompts.
	 * 
	 * 
	 * https://generativelanguage.googleapis.com/v1beta/models/text-bison@001:streamGenerateContent
	 * 
	 * text-bison@001: This is a smaller, more specialized model designed primarily 
	 * for conversational AI and chatbot applications. It focuses on generating 
	 * natural-sounding, human-like responses that are appropriate for dialogue.
	 * 
	 ************************************************************* */
	 private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent";
	//private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/text-bison@001:streamGenerateContent";
	
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
				.headers("Content-Type", "application/json")
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
		// Add configuration settings here
		/*GenerationConfig config = new GenerationConfig(
				1, // temperature
				0.95, // topP
				64, // topK
				8192, // maxOutputTokens
				"text/plain" // responseMIMEType
				);*/
		GeminiRequest request = new GeminiRequest();
		Content content = new Content();
		Part part = new Part();
		part.text = prompt; // Set the prompt text
		content.parts = List.of(part);
		 content.role = "user"; 
		request.contents = new Content[] {content};
		//request.generationConfig = config;
		// Print the JSON request body for debugging
        //System.out.println("Request Body:");
        //System.out.println(gson.toJson(request));

        return gson.toJson(request);
	}

	// Method to process response 
	private static void processResponse(HttpResponse<String> response) throws IOException {
		if (response.statusCode() != 200) {
			System.out.println("Error: " + response.statusCode()+ " - " + HttpStatus.getMessageFromCode(response.statusCode()));
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
	public GenerationConfig generationConfig;
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

record GenerationConfig (
		double temperature,
		double topP,
		int topK,
		int maxOutputTokens,
		String responseMIMEType) {}

