package dev.anonymous.eilaji.doctor.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.firebase.ui.database.FirebaseRecyclerOptions;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.StorageReference;

import java.util.HashMap;
import java.util.Map;

import dev.anonymous.eilaji.doctor.Notification.FCMSend;
import dev.anonymous.eilaji.doctor.adapters.MessagesAdapter;
import dev.anonymous.eilaji.doctor.databinding.ActivityMessagingBinding;
import dev.anonymous.eilaji.doctor.firebase.FirebaseChatController;
import dev.anonymous.eilaji.doctor.models.ChatModel;
import dev.anonymous.eilaji.doctor.models.MessageModel;
import dev.anonymous.eilaji.doctor.storage.ChatSharedPreferences;
import dev.anonymous.eilaji.doctor.utils.MyScrollToBottomObserver;
import dev.anonymous.eilaji.doctor.utils.constants.Constant;
import dev.anonymous.eilaji.doctor.utils.interfaces.ListenerCallback;

public class MessagingActivity extends AppCompatActivity {
    private static final String TAG = "MessagingActivity";
    private ActivityMessagingBinding binding;
    private StorageReference messagesImagesRef;
    private DatabaseReference chatListRef;
    private DatabaseReference chatRef;
    private MessagesAdapter messagesAdapter;
    private String chatId;
    private String userUid,
            userFullName,
            userUrlImage,
            userToken;

    private String receiverUid,
            receiverFullName,
            receiverUrlImage,
            receiverToken;

    private final ChatSharedPreferences chatSharedPreferences = ChatSharedPreferences.getInstance();
    private final FirebaseChatController firebaseChatController = FirebaseChatController.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMessagingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        var user = firebaseChatController.getCurrentUser();
        if (user != null) {
            userUid = user.getUid();

            userFullName = user.getDisplayName();
            userUrlImage = String.valueOf(user.getPhotoUrl());
            //TODO
            userToken = chatSharedPreferences.getToken();
//            userToken = preferences.getString("token", null);

            Intent intent = getIntent();
            if (intent != null) {
                chatId = intent.getStringExtra("chat_id");
                receiverUid = intent.getStringExtra("receiver_uid");
                receiverFullName = intent.getStringExtra("receiver_full_name");
                receiverUrlImage = intent.getStringExtra("receiver_image_url");
                receiverToken = intent.getStringExtra("receiver_token");
            }

            System.out.println("Token: " + userToken);
            System.out.println("receiverFullName: " + receiverFullName);
            System.out.println("userFullName: " + userFullName);

            FirebaseDatabase database = FirebaseDatabase.getInstance();
            chatListRef = database.getReference(Constant.CHAT_LIST_DOCUMENT);
            chatRef = database.getReference(Constant.CHATS_DOCUMENT);

//            messagesImagesRef = FirebaseStorage.getInstance()
//                    .getReference(Constant.MESSAGES_IMAGES_DOCUMENT);

            checkChatExist(userUid, receiverUid);

            binding.buSendMessage.setOnClickListener(v -> {
                buttonSendMessage();
            });

            binding.buSendImage.setOnClickListener(v -> {
                buttonSendImage();
            });
        }
    }

    private void buttonSendImage() {
        PickVisualMediaRequest mediaRequest = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        pickMedia.launch(mediaRequest);
    }

    private void buttonSendMessage() {
        if (chatId != null) {
            String message = binding.edMessage.getText().toString().trim();

            if (!TextUtils.isEmpty(message)) {
                binding.edMessage.setText("");
                sendMessage(userUid, receiverUid, message, null);
            }
        }
    }

    //
    @Override
    protected void onResume() {
        if (receiverUid != null) {
            chatSharedPreferences.putCurrentUserChattingUID(receiverUid);
//            preferences.edit().putString(Constant.CURRENT_USER_CHATTING_UID, receiverUid).apply();
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (receiverUid != null) {
            chatSharedPreferences.removeCurrentUserChattingUID();
//            preferences.edit().remove(Constant.CURRENT_USER_CHATTING_UID).apply();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (messagesAdapter != null) {
            messagesAdapter.stopListening();
        }
        super.onDestroy();
    }

    ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null) {
                            sendMessage(userUid, receiverUid, null, uri);
                        }
                    });

    private void sendMessage(String senderUid, final String receiverUid, String message, Uri imageUri) {
        if (TextUtils.isEmpty(chatId)) {
            String id = chatRef.push().getKey();
            if (id != null) {
                Map<String, Object> chatData = new HashMap<>();
                chatData.put("sender_uid", senderUid);
                chatData.put("receiver_uid", receiverUid);

                chatRef.child(id)
                        .setValue(chatData)
                        .addOnCompleteListener(command -> {
                            if (command.isSuccessful()) {
                                Toast.makeText(this, "تم انشاء قالب المحادثة", Toast.LENGTH_SHORT).show();
                                chatId = id;
                                if (imageUri != null) {

                                    uploadImageMassage(senderUid, receiverUid, imageUri);

                                } else {
                                    addMessageToChat(
                                            senderUid,
                                            receiverUid,
                                            message,
                                            null,
                                            null
                                    );
                                }
                            }
                        }).addOnFailureListener(e -> Log.e(TAG, "sendMessage: " + e.getMessage()));
            }
        } else {
            if (imageUri != null) uploadImageMassage(senderUid, receiverUid, imageUri);
            else addMessageToChat(senderUid, receiverUid, message, null, null);
        }
    }

    private void uploadImageMassage(String senderUid, final String receiverUid, Uri imageUri) {
        final String imageName = senderUid + "::" + System.currentTimeMillis();
        StorageReference pdfRef = messagesImagesRef.child(imageName + ".jpg");

        pdfRef.putFile(imageUri)
                .addOnProgressListener(taskSnapshot -> {
                    double progress = (100.0 * taskSnapshot.getBytesTransferred()) / taskSnapshot.getTotalByteCount();
                    System.out.println(progress);
                }).continueWithTask(task -> {
                    if (!task.isSuccessful() && task.getException() != null) {
                        throw task.getException();
                    }
                    return pdfRef.getDownloadUrl();
                }).addOnSuccessListener(taskSnapshot -> {
                    Toast.makeText(this, "تم رفع الصورة بنجاح", Toast.LENGTH_SHORT).show();
                    addMessageToChat(
                            senderUid,
                            receiverUid,
                            null,
                            taskSnapshot.toString(),
                            null
                    );
                }).addOnFailureListener(exception -> Log.e(TAG, "addImageToChat: " + exception.getMessage()));
    }

    private void addMessageToChat(String senderUid, final String receiverUid, String message,
                                  String imageUrl, String medicineName) {
        MessageModel model = new MessageModel(
                senderUid,
                receiverUid,
                message,
                imageUrl,
                medicineName,
                System.currentTimeMillis()
        );

        chatRef.child(chatId)
                .child(Constant.CHATS_CHILD_CHAT)
                .push()
                .setValue(model)
                .addOnCompleteListener(command -> {
                    if (command.isSuccessful()) {
                        Toast.makeText(this, "تم ارسال الرسالة بنجاح", Toast.LENGTH_SHORT).show();

                        FCMSend.pushNotificationToToken(
                                this,
                                userToken,
                                userUid,
                                userFullName,
                                message,
                                userUrlImage,
                                imageUrl
                        );

                        updateChatList(senderUid, receiverUid, message, imageUrl);
                    } else {
                        Exception exception = command.getException();
                        if (exception != null) {
                            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }).addOnFailureListener(e ->
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void updateChatList(String senderUid, final String receiverUid, String lastMessageText,
                                String lastMessageImage) {
        ChatModel model = new ChatModel(
                chatId,
                lastMessageText,
                lastMessageImage,
                senderUid,
                receiverFullName,
                receiverUrlImage,
                receiverToken,
                System.currentTimeMillis()
        );

        chatListRef.child(senderUid)
                .child(receiverUid)
                .setValue(model)
                .addOnCompleteListener(command -> {
                    if (command.isSuccessful()) {
                        Toast.makeText(this, "تم تحديث قائمة المرسل", Toast.LENGTH_SHORT).show();
                    }
                });

        model.setUserFullName(userFullName);
        model.setUserImageUrl(userUrlImage);
        model.setUserToken(userToken);

        chatListRef.child(receiverUid)
                .child(senderUid)
                .setValue(model)
                .addOnCompleteListener(command -> {
                    if (command.isSuccessful()) {
                        Toast.makeText(this, "تم تحديث قائمة المستقبل", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    void checkChatExist(String senderUid, final String receiverUid) {
        final DatabaseReference reference = chatListRef.child(senderUid).child(receiverUid);

        reference.get().addOnCompleteListener(command -> {
            binding.progressMessaging.setVisibility(View.GONE);
            if (command.isSuccessful()) {
                if (command.getResult().exists()) {
                    Object chatIdValue = command.getResult()
                            .child(Constant.CHAT_LIST_CHILD_CHAT_ID)
                            .getValue();
                    if (chatIdValue != null) {
                        chatId = chatIdValue.toString();
                        setupMessagesAdapter();
                    }
                } else {
                    chatId = "";
                }
            } else {
                Exception exception = command.getException();
                if (exception != null) {
                    Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }).addOnFailureListener(e ->
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void setupMessagesAdapter() {
        var chatController = FirebaseChatController.getInstance();
        DatabaseReference currentChatRef = chatController.database
                .getReference(Constant.CHATS_DOCUMENT)
                .child(Constant.CHATS_CHILD_CHAT);

        FirebaseRecyclerOptions<MessageModel> options = new FirebaseRecyclerOptions.Builder<MessageModel>()
                .setQuery(currentChatRef, MessageModel.class)
                .build();

        boolean isRTL = binding.recyclerMessaging.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        messagesAdapter = new MessagesAdapter(options, userUid, isRTL);
        // fix => Inconsistency detected. Invalid view holder adapter
//        LinearLayoutManager manager = new WrapContentLinearLayoutManager(this);
        LinearLayoutManager manager = new LinearLayoutManager(this);
        // scroll to end recycler
        manager.setStackFromEnd(true);
        binding.recyclerMessaging.setLayoutManager(manager);
        binding.recyclerMessaging.setAdapter(messagesAdapter);

        // scroll to Bottom when insert message to chat
        messagesAdapter.registerAdapterDataObserver(
                new MyScrollToBottomObserver(binding.recyclerMessaging, messagesAdapter, manager)
        );

        messagesAdapter.startListening();
    }


    ///******************************************

    private void uploadImageM(Uri imageUri) {
        firebaseChatController.uploadImageMassage(userUid, receiverUid, imageUri, new ListenerCallback() {
            @Override
            public void onSuccess() {

            }

            @Override
            public void onFailure(String message) {

            }
        });
    }


}