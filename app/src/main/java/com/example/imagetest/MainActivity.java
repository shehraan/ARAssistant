//MainActivity.java

package com.example.imagetest;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;

import okhttp3.*;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_IMAGE_REQUEST = 1;

    private EditText textInput;
    private ImageView imagePreview;
    private TextView outputTextBox;
    private Uri imageUri;

    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private Intent intent;

    private Bitmap resizeImage(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float bitmapRatio = (float) width / (float) height;
        if (bitmapRatio > 1) {
            width = maxWidth;
            height = (int) (width / bitmapRatio);
        } else {
            height = maxHeight;
            width = (int) (height * bitmapRatio);
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textInput = findViewById(R.id.textInput);
        imagePreview = findViewById(R.id.imagePreview);
        outputTextBox = findViewById(R.id.outputTextBox);

        Button selectImageButton = findViewById(R.id.selectImageButton);
        Button submitButton = findViewById(R.id.submitButton);

        // Initialize TextToSpeech
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> textToSpeech.setSpeechRate(0.8f));

        // Initialize SpeechRecognizer
        intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle bundle) {
                ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String recognizedText = matches.get(0);
                    textInput.setText(recognizedText);
                    if (imageUri != null) {
                        sendToChatGPT(recognizedText, imageUri);
                    }
                }
            }

            @Override public void onReadyForSpeech(Bundle bundle) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] bytes) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int i) {}
            @Override public void onPartialResults(Bundle bundle) {}
            @Override public void onEvent(int i, Bundle bundle) {}
        });

        // Handle image selection
        selectImageButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        // Handle voice input and submission
        submitButton.setOnClickListener(v -> {
            if (textToSpeech.isSpeaking()) {
                textToSpeech.stop();
            } else {
                speechRecognizer.startListening(intent);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            imageUri = data.getData();
            imagePreview.setImageURI(imageUri);
            imagePreview.setVisibility(View.VISIBLE);
        }
    }

    private void sendToChatGPT(String text, Uri imageUri) {
        try {
            // Convert image to Base64
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Bitmap resizedBitmap = resizeImage(bitmap, 400, 400); // Resize to 400x400 max
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream);
            String base64Image = Base64.getEncoder().encodeToString(outputStream.toByteArray());

            // Construct JSON payload
            JSONObject imageObject = new JSONObject();
            imageObject.put("url", "data:image/jpeg;base64," + base64Image);

            JSONArray contentArray = new JSONArray();
            JSONObject textContent = new JSONObject();
            textContent.put("type", "text");
            textContent.put("text", text);

            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageObject);

            contentArray.put(textContent);
            contentArray.put(imageContent);

            JSONObject messageObject = new JSONObject();
            messageObject.put("role", "user");
            messageObject.put("content", contentArray);

            JSONArray messages = new JSONArray();
            messages.put(messageObject);

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "gpt-4o");
            jsonBody.put("messages", messages);

            // Create API request
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .post(RequestBody.create(MediaType.parse("application/json"), jsonBody.toString()))
                    .addHeader("Authorization", "Bearer ...API-KEY")
                    .addHeader("Content-Type", "application/json")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> outputTextBox.setText("Error: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    if (response.isSuccessful()) {
                        try {
                            JSONObject jsonResponse = new JSONObject(responseBody);
                            String content = jsonResponse.getJSONArray("choices").getJSONObject(0)
                                    .getJSONObject("message").getString("content");
                            runOnUiThread(() -> {
                                outputTextBox.setText(content);
                                textToSpeech.speak(content, TextToSpeech.QUEUE_FLUSH, null, null);
                            });
                        } catch (JSONException e) {
                            runOnUiThread(() -> outputTextBox.setText("Error parsing response: " + e.getMessage()));
                        }
                    } else {
                        runOnUiThread(() -> outputTextBox.setText("Error: " + response.code() + "\n" + responseBody));
                    }
                }
            });
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> outputTextBox.setText("Error: " + e.getMessage()));
        }
    }


}
