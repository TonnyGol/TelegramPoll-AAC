package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;

public class MyBot extends TelegramLongPollingBot {
    private final Set<Long> communityMembers = new HashSet<>();
    private boolean surveyActive = false;
    private long surveyCreatorId;
    private Map<String, List<String>> surveyQuestions = new LinkedHashMap<>();
    private Map<Long, Map<String, String>> surveyResponses = new HashMap<>();
    private long surveyStartTime;
    private static final int RESPONSE_TIMEOUT = 5 * 60 * 1000; // 5 minutes
    private int currentQuestionIndex = 0;
    private String currentQuestion = null;
    private boolean awaitingLaunchChoice = false;
    private boolean waitToSend = false;

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                String messageText = update.getMessage().getText();
                long chatId = update.getMessage().getChatId();

                if (messageText.equalsIgnoreCase("start") ||
                        messageText.equalsIgnoreCase("היי") ||
                        messageText.equalsIgnoreCase("Hi")) {
                    if (communityMembers.add(chatId)) {
                        notifyCommunityAboutNewMember(chatId);
                    }
                } else if (messageText.equalsIgnoreCase("/createpoll")) {
                    handleCreatePollCommand(chatId);
                } else if (surveyActive && chatId == surveyCreatorId) {
                    if (awaitingLaunchChoice) {
                        handleLaunchChoice(chatId, messageText);
                    } else {
                        handlePollCreationSteps(chatId, messageText);
                    }
                } else {
                    sendMessage(chatId, "Unrecognized command or you are not authorized to perform this action.");
                }
            } else if (update.hasCallbackQuery()) {
                String callbackData = update.getCallbackQuery().getData();
                long userId = update.getCallbackQuery().getFrom().getId();
                String[] callbackParts = callbackData.split("_", 2);
                String question = callbackParts[0];
                String selectedOption = callbackParts[1];
                handleSurveyResponse(userId, question, selectedOption);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyCommunityAboutNewMember(long newMemberId) {
        int communitySize = communityMembers.size();
        String messageText = "A new member has joined the community! Current size: " + communitySize;
        messageText += "\nYou can now add a poll for everyone using /createpoll";

        for (Long memberId : communityMembers) {
            sendMessage(memberId, messageText);
        }
    }

    private void handleCreatePollCommand(long chatId) {
        if (surveyActive) {
            sendMessage(chatId, "There is already a survey in progress. Please wait until it finishes.");
        } else if (communityMembers.size() < 3) {
            sendMessage(chatId, "You need at least 3 members in the community to create a survey.");
        } else {
            surveyCreatorId = chatId;
            surveyQuestions.clear();
            currentQuestionIndex = 0;
            sendMessage(chatId, "Please enter the first question for the survey:");
            surveyActive = true;
        }
    }

    private void handlePollCreationSteps(long chatId, String messageText) {
        if (currentQuestion == null && !messageText.equalsIgnoreCase("finish")) {
            currentQuestion = messageText;
            List<String> options = new ArrayList<>();
            surveyQuestions.put(currentQuestion, options);
            sendMessage(chatId, "Please enter the first option for this question:");
        } else if (messageText.equalsIgnoreCase("done")) {
            if (surveyQuestions.get(currentQuestion).size() < 2) {
                sendMessage(chatId, "You need to provide at least 2 options before completing the question.");
            } else {
                currentQuestion = null;
                if (currentQuestionIndex < 2) {
                    currentQuestionIndex++;
                    sendMessage(chatId, "Please enter the next question or type 'finish' to finish poll:");
                } else {
                    sendMessage(chatId, "Survey creation complete. Do you want to launch the survey now or schedule it for later? Type 'now' or 'later'.");
                    awaitingLaunchChoice = true;
                }
            }
        } else if (messageText.equalsIgnoreCase("finish")){
            sendMessage(chatId, "Survey creation complete. Do you want to launch the survey now or schedule it for later? Type 'now' or 'later'.");
            awaitingLaunchChoice = true;
        } else {
            List<String> options = surveyQuestions.get(currentQuestion);
            if (options.size() < 4) {
                options.add(messageText);
                if (options.size() == 2) {
                    sendMessage(chatId, "You can now type 'done' to finish this question or enter up to 2 more options.");
                } else if (options.size() == 4) {
                    sendMessage(chatId, "Maximum number of options reached. Please type 'done' to finish this question.");
                } else {
                    sendMessage(chatId, "Please enter another option or type 'done' to finish this question.");
                }
            } else {
                sendMessage(chatId, "You have already entered the maximum of 4 options. Please type 'done' to finish this question.");
            }
        }
    }

    private void handleLaunchChoice(long chatId, String messageText) {
        if (messageText.equalsIgnoreCase("now")) {
            sendSurveyToCommunity();
            awaitingLaunchChoice = false;
        } else if (messageText.equalsIgnoreCase("later")) {
            sendMessage(chatId, "Survey scheduled. Please enter the delay in minutes:");
            waitToSend = true;
        } else {
            if (waitToSend){
                awaitingLaunchChoice = false;
                scheduleSurvey(chatId, messageText); // You can implement this as needed
            }
            if (awaitingLaunchChoice){
                sendMessage(chatId, "Please type 'now' to launch the survey immediately or 'later' to schedule it.");
            }
        }
    }

    private void sendSurveyToCommunity() {
        for (Map.Entry<String, List<String>> entry : surveyQuestions.entrySet()) {
            String question = entry.getKey();
            List<String> options = entry.getValue();

            InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

            for (String option : options) {
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(option);
                button.setCallbackData(question + "_" + option);

                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(button);
                rowsInline.add(rowInline);
            }

            inlineKeyboardMarkup.setKeyboard(rowsInline);

            for (Long memberId : communityMembers) {
                SendMessage message = new SendMessage();
                message.setChatId(memberId);
                message.setText("Question: \"" + question + "\"");
                message.setReplyMarkup(inlineKeyboardMarkup);
                try {
                    execute(message);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        surveyStartTime = System.currentTimeMillis();
        scheduleSurveyResults(); // Schedule the result sending
    }

    private void scheduleSurveyResults() {
        new Thread(()-> {
            while (surveyActive){
                if (surveyResponses.size() == communityMembers.size() || (System.currentTimeMillis() - surveyStartTime) > RESPONSE_TIMEOUT) {
                    sendSurveyResultsToCreator();
                    surveyActive = false;
                    currentQuestion = null;
                }
            }
        }).start();
    }

    private void scheduleSurvey(long chatId, String messageText) {
        // Implement scheduling logic here, e.g., using a ScheduledExecutorService
        sendMessage(chatId, "Survey will be sent later as per your schedule.");
        int waitTime = Integer.parseInt(messageText) * 60 * 1000;
        long scheduleSurveyStartTime = System.currentTimeMillis();
        new Thread(()->{
            while (waitToSend){
                if ((System.currentTimeMillis() - scheduleSurveyStartTime) > waitTime){
                    sendSurveyToCommunity();
                    waitToSend = false;
                }
            }
        }).start();
    }

    private void handleSurveyResponse(long chatId, String question, String selectedOption) {
        surveyResponses.putIfAbsent(chatId, new HashMap<>());
        Map<String, String> userResponses = surveyResponses.get(chatId);
        if (userResponses.containsKey(question)) {
            sendMessage(chatId, "You have already answered this question.");
        } else {
            userResponses.put(question, selectedOption);
            sendMessage(chatId, "Your response has been recorded.");
        }
    }

    private void sendSurveyResultsToCreator() {
        StringBuilder results = new StringBuilder("Survey Results:\n");
        for (Map.Entry<String, List<String>> entry : surveyQuestions.entrySet()) {
            String question = entry.getKey();
            List<String> options = entry.getValue();
            results.append(question).append("\n");

            Map<String, Integer> answerCounts = new HashMap<>();
            for (Map<String, String> responses : surveyResponses.values()) {
                String answer = responses.get(question);
                if (answer != null) {
                    answerCounts.put(answer, answerCounts.getOrDefault(answer, 0) + 1);
                }
            }

            answerCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> results.append("Question - ").append(e.getKey()).append(":  voted - ").append(e.getValue()).append("\n"));
        }

        sendMessage(surveyCreatorId, results.toString());
    }

    @Override
    public String getBotUsername() {
        return "YOUR BOT USERNAME HERE";
    }

    @Override
    public String getBotToken() {
        return "YOUR TOKEN HERE";
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
