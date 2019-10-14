package no.ntnu.datakomm.chat;

import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    // Hint: if you want to store a message for the last error, store it here
    private String lastError = null;

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        boolean connected = false;
        try
        {
            this.connection = new Socket(host, port);
            // If the connection succeeds, set up input- and output streams,
            // and use them to read the communication between server and client.
            if (this.connection.isConnected())
            {
                InputStream in = connection.getInputStream();
                this.fromServer = new BufferedReader(new InputStreamReader(in));
                OutputStream out = connection.getOutputStream();
                this.toServer = new PrintWriter(out, true);
                connected = true;
            }
        } catch (IOException e)
        {
            System.out.println("Socket error: " + e.getMessage());
        }
        return connected;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        if (isConnectionActive())
        {
            try
            {
                connection.close();
                connection = null;
                toServer = null;
                fromServer = null;
                onDisconnect();
            }
            catch (IOException e)
            {
                System.out.println("Socket error: " + e.getMessage());
            }
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        boolean commandSent = false;
        if(isConnectionActive())
            {
                this.toServer.println(cmd);
                commandSent = true;
            }
        return commandSent;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {

        boolean messageSent = false;
        String messageToSend = "msg " + message;

        if(sendCommand(messageToSend))
            {
                messageSent = true;
            }
        return messageSent;
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        if (this.connection.isConnected())
        {
            sendCommand("login " + username);
        }
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        if(isConnectionActive())
            {
                sendCommand("users ");
            }
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        boolean result = false;
        result = sendCommand("privmsg "  + recipient + " " + message);
        return result;
    }


    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        // TODO Step 8: Implement this method
        // Hint: Reuse sendCommand() method
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String response = null;
        try
        {
            if (this.connection.isConnected())
            {
                response = this.fromServer.readLine();
            }
        }
        catch (IOException | NullPointerException e)
        {
            System.out.println("Stream error: " + e.getMessage());
            disconnect();
        }
        return response;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(() -> {
            parseIncomingCommands();
        });
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {

            String serverResponse = waitServerResponse();


            if (serverResponse != null)
                {
                    String[] serverResponseArr = serverResponse.split(" ", 2);
                    String inputCase = serverResponseArr[0];
                    String extraParameters;
                    String[] extraParametersArr;
                    String sender;
                    String text;

                    switch (inputCase)
                        {
                            case "loginok":
                                onLoginResult(true, "");
                                break;

                            case "loginerr":
                                extraParameters = serverResponseArr[1];
                                onLoginResult(false, extraParameters);
                                break;

                            case "users":
                                extraParameters = serverResponseArr[1];
                                String[] users = extraParameters.split(" ");
                                onUserList(users);
                                break;

                            case "msg":
                                extraParameters = serverResponseArr[1];
                                extraParametersArr = extraParameters.split(" ", 2);
                                sender = extraParametersArr[0];
                                text = extraParametersArr[1];
                                onMsgReceived(false, sender, text);
                                break;

                            case "privmsg":
                                extraParameters = serverResponseArr[1];
                                extraParametersArr = extraParameters.split(" ", 2);
                                sender = extraParametersArr[0];
                                text = extraParametersArr[1];
                                onMsgReceived(true, sender, text);
                                break;

                            case "msgerr":
                                extraParameters = serverResponseArr[1];
                                onMsgError(extraParameters);
                                break;

                            case "cmderr":
                                extraParameters = serverResponseArr[1];
                                onCmdError(extraParameters);
                                break;

                            case "supported":
                                extraParameters = serverResponseArr[1];
                                String[] supportedCommands = extraParameters.split(" ");
                                onSupported(supportedCommands);
                                break;

                            default:
                                break;
                        }
                }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        for (ChatListener l : listeners)
        {
            l.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUserList(String[] users) {
        for(ChatListener l : listeners)
            {
                l.onUserList(users);
            }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage textMessage = new TextMessage(sender, priv, text);
        for (ChatListener l : listeners)
        {
            l.onMessageReceived(textMessage);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        // TODO Step 7: Implement this method
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        // TODO Step 7: Implement this method
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        // TODO Step 8: Implement this method
    }
}
