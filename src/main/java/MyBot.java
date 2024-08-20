package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MyBot extends TelegramLongPollingBot {
    private final int MINIMUM_OPTIONS = 2;
    private final  int MAXIMUM_OPTIONS = 4;
    private final int MINIMUM_MEMBERS_FOR_POLL_CREATION = 3;
    private final int RESPONSE_TIMEOUT = 5 * 60 * 1000; // 5 minutes

    private Set<Long> communityMembers = new HashSet<>();
    private Map<Long, Map<String, String>> surveyResponses = new ConcurrentHashMap<>();
    private boolean surveyActive = false;
    private long surveyCreatorId;
    private Map<String, List<String>> surveyQuestions = new LinkedHashMap<>();
    private long surveyStartTime;
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
                    if (messageText.equalsIgnoreCase("/start")) {
                        sendJoinMessage(chatId);
                    } else {
                        sendMessage(chatId, "Unrecognized command or you are not authorized to perform this action.");
                    }
                }
            } else if (update.hasCallbackQuery()) {
                String callbackData = update.getCallbackQuery().getData();
                long chatId = update.getCallbackQuery().getMessage().getChatId();

                if (callbackData.equals("done")) {
                    handlePollCreationSteps(chatId, "done");
                } else if (callbackData.equals("finish")) {
                    handlePollCreationSteps(chatId, "finish");
                } else if (callbackData.equals("now")) {
                    handleLaunchChoice(chatId, "now");
                } else if (callbackData.equals("later")) {
                    handleLaunchChoice(chatId, "later");
                } else if (callbackData.equals("join")) {
                    if (communityMembers.add(chatId)) {
                        notifyCommunityAboutNewMember(chatId);
                        sendMessage(chatId, "You have joined the community!");
                    } else {
                        sendMessage(chatId, "You are already a member of the community.");
                    }
                } else {
                    long userId = update.getCallbackQuery().getFrom().getId();
                    String[] callbackParts = callbackData.split("_", 2);
                    String question = callbackParts[0];
                    String selectedOption = callbackParts[1];
                    handleSurveyResponse(userId, question, selectedOption);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendJoinMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Hello new user, you can enter the Community by typing \n" +
                "(start, Hi, היי) or by pressing the button below");

        // Create an InlineKeyboardMarkup for the "Join" button
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        InlineKeyboardButton joinButton = new InlineKeyboardButton();
        joinButton.setText("Join");
        joinButton.setCallbackData("join");

        List<InlineKeyboardButton> keyboardButtonsRow = new ArrayList<>();
        keyboardButtonsRow.add(joinButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();
        rowList.add(keyboardButtonsRow);

        inlineKeyboardMarkup.setKeyboard(rowList);
        message.setReplyMarkup(inlineKeyboardMarkup);

        try {
            execute(message);
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
        } else if (communityMembers.size() < MINIMUM_MEMBERS_FOR_POLL_CREATION) {
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
            if (surveyQuestions.get(currentQuestion).size() < MINIMUM_OPTIONS) {
                sendMessage(chatId, "You need to provide at least 2 options before completing the question.");
            } else {
                currentQuestion = null;
                if (currentQuestionIndex < 2) {
                    currentQuestionIndex++;
                    sendMessage(chatId, "Please enter the next question or type 'finish' to finish poll:");
                    // Provide 'finish' button
                    sendFinishButton(chatId);
                } else {
                    sendMessage(chatId, "Survey creation complete. Do you want to launch the survey now or schedule it for later?");
                    // Provide 'now' and 'later' buttons
                    sendLaunchOptionButtons(chatId);
                    awaitingLaunchChoice = true;
                }
            }
        } else if (messageText.equalsIgnoreCase("finish")) {
            sendMessage(chatId, "Survey creation complete. Do you want to launch the survey now or schedule it for later?");
            sendLaunchOptionButtons(chatId);
            awaitingLaunchChoice = true;
        } else {
            List<String> options = surveyQuestions.get(currentQuestion);
            if (options.size() < MAXIMUM_OPTIONS) {
                options.add(messageText);
                if (options.size() == MINIMUM_OPTIONS) {
                    sendMessage(chatId, "You can now type 'done' to finish this question or enter up to 2 more options.");
                    // Provide 'done' button
                    sendDoneButton(chatId);
                } else if (options.size() == 4) {
                    sendMessage(chatId, "Maximum number of options reached. Please type 'done' to finish this question.");
                    sendDoneButton(chatId);
                } else {
                    sendMessage(chatId, "Please enter another option or type 'done' to finish this question.");
                    sendDoneButton(chatId);
                }
            } else {
                sendMessage(chatId, "You have already entered the maximum of 4 options. Please type 'done' to finish this question.");
                sendDoneButton(chatId);
            }
        }
    }


    private void sendDoneButton(long chatId) {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();
        InlineKeyboardButton doneButton = new InlineKeyboardButton();
        doneButton.setText("Done");
        doneButton.setCallbackData("done");
        rowInline.add(doneButton);
        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Please press 'done' when ready.");
        message.setReplyMarkup(markupInline);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendFinishButton(long chatId) {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();
        InlineKeyboardButton finishButton = new InlineKeyboardButton();
        finishButton.setText("Finish");
        finishButton.setCallbackData("finish");
        rowInline.add(finishButton);
        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Press 'Finish' when you are ready to complete the poll.");
        message.setReplyMarkup(markupInline);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendLaunchOptionButtons(long chatId) {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> rowInline1 = new ArrayList<>();
        InlineKeyboardButton nowButton = new InlineKeyboardButton();
        nowButton.setText("Now");
        nowButton.setCallbackData("now");
        rowInline1.add(nowButton);

        List<InlineKeyboardButton> rowInline2 = new ArrayList<>();
        InlineKeyboardButton laterButton = new InlineKeyboardButton();
        laterButton.setText("Later");
        laterButton.setCallbackData("later");
        rowInline2.add(laterButton);

        rowsInline.add(rowInline1);
        rowsInline.add(rowInline2);

        markupInline.setKeyboard(rowsInline);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Please choose when to launch the survey:");
        message.setReplyMarkup(markupInline);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
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
                scheduleSurvey(chatId, messageText);
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
        new Thread(() -> {
            while (surveyActive) {
                boolean allMembersAnswered = surveyResponses.size() == communityMembers.size();
                boolean allQuestionsAnswered = true;

                for (Map<String, String> userResponses : surveyResponses.values()) {
                    if (userResponses.size() < surveyQuestions.size()) {
                        allQuestionsAnswered = false;
                        break;
                    }
                }

                if (allMembersAnswered && allQuestionsAnswered) {
                    sendSurveyResultsToCreator();
                    surveyActive = false;
                    currentQuestion = null;
                } else if ((System.currentTimeMillis() - surveyStartTime) > RESPONSE_TIMEOUT) {
                    sendSurveyResultsToCreator();
                    surveyActive = false;
                    currentQuestion = null;
                }
            }
        }).start();
    }

    private void scheduleSurvey(long chatId, String messageText) {
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
            results.append("Q: ").append(question).append("\n");

            Map<String, Integer> answerCounts = new HashMap<>();
            int totalResponses = surveyResponses.size();

            for (Map<String, String> responses : surveyResponses.values()) {
                String answer = responses.get(question);
                if (answer != null) {
                    answerCounts.put(answer, answerCounts.getOrDefault(answer, 0) + 1);
                }
            }

            List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>();
            for (String option : options) {
                int voteCount = answerCounts.getOrDefault(option, 0);
                double percentage = totalResponses > 0 ? (voteCount * 100.0) / communityMembers.size() : 0.0;
                sortedEntries.add(new AbstractMap.SimpleEntry<>(option, percentage));
            }

            // Sort the options by percentage in descending order
            sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));

            for (Map.Entry<String, Double> entry1 : sortedEntries) {
                results.append(String.format("Answer: (%s): voted %.2f%%\n", entry1.getKey(), entry1.getValue()));
            }

            results.append("\n");
        }
    }


    @Override
    public String getBotUsername() {
        return "YOUR BOT USERNAME";
    }

    @Override
    public String getBotToken() {
        return "YOUR BOT TOKEN";
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
