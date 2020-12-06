import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ThreadServerSocket extends Thread {
    private final Socket inSocket;

    public ThreadServerSocket(Socket inSocket) {
        this.inSocket = inSocket;
        this.start();
    }

    @Override
    public void run() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(inSocket.getInputStream(), StandardCharsets.UTF_8)); PrintWriter output = new PrintWriter(inSocket.getOutputStream())) {
            String request = input.readLine();
            if (isGoodRequest(request, output)) {
                toRequest(request, input, output);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        try {
            inSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static boolean isGoodRequest(String request, PrintWriter output) throws IOException {
        if (request == null) {
            output.println("HTTP/1.1 400 Bad Request");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Отправлен неверный запрос.");
            return false;
        }
        return true;
    }

    private static void toRequest(String request, BufferedReader input, PrintWriter output) throws IOException, NoSuchAlgorithmException {
        String authHeader = "Authorization: ";
        String authLine = "";
        String contentLengthHeader = "Content-Length: ";
        int postDataIndex = -1;
        String ifNonMatchHeader = "If-None-Match: ";
        String ifNonMatchMD5 = "";
        String ifModifiedSinceHeader = "If-Modified-Since: ";
        String ifModifiedSinceData = "";

        String line;
        while ((line = input.readLine()) != null && (line.length() != 0)) {
            if (line.contains(authHeader)) {
                authLine = line.substring(authHeader.length());
            } else if (line.contains(contentLengthHeader)) {
                postDataIndex = Integer.parseInt(line.substring(contentLengthHeader.length()));
            } else if (line.contains(ifNonMatchHeader)) {
                ifNonMatchMD5 = line.substring(ifNonMatchHeader.length());
            } else if (line.contains(ifModifiedSinceHeader)) {
                ifModifiedSinceData = line.substring(ifModifiedSinceHeader.length());
            }
        }

        String rootFolder = getRootFolder();
        int endIndexOfRequest = request.indexOf("HTTP") - 1;
        String clippedRequest = request.substring(0, endIndexOfRequest);
        String[] requestMethods = {"GET", "POST", "DELETE"};
        for (String requestMethod : requestMethods) {
            if (request.contains(requestMethod)) {
                String replacement = rootFolder + clippedRequest.substring(requestMethod.length() + 1);
                String path = replacement.replace("/", "\\");
                if (isCorrectlyAuthorization(authLine, output)) {
                    switch (requestMethod) {
                        case "GET" -> {
                            if (isContainsNonMatchOrIfModifiedSinceHeader(ifNonMatchMD5, ifModifiedSinceData, output)) {
                                toGET(output, path, ifNonMatchMD5, ifModifiedSinceData);
                            }
                        }
                        case "POST" -> {
                            if (isContainsContentLengthHeader(postDataIndex, output)) {
                                toPOST(input, output, postDataIndex, path);
                            }
                        }
                        case "DELETE" -> toDELETE(output, path);
                    }
                }
            }
        }
    }

    static boolean isCorrectlyAuthorization(String authorizationLine, PrintWriter output) throws IOException {
        if (authorizationLine.equals("")) {
            output.println("HTTP/1.1 400 Bad Request");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Не указаны необходимые заголовки.");
            return false;
        }
        String login = authorizationLine.substring(0, authorizationLine.indexOf(','));
        String password = authorizationLine.substring(authorizationLine.indexOf(',') + 1);
        String config = new String(Files.readAllBytes(Paths.get("configuration.txt")));
        List<String> lines = config.lines().collect(Collectors.toList());
        if (!lines.get(0).equals(login) || !lines.get(1).equals(password)) {
            output.println("HTTP/1.1 401 Unauthorized");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Логин или пароль неверны.");
            return false;
        }
        return true;

    }

    static boolean isContainsNonMatchOrIfModifiedSinceHeader(String ifNonMatchMD5, String ifModifiedSinceData, PrintWriter output) {
        if (ifNonMatchMD5.equals("") || ifModifiedSinceData.equals("")) {
            output.println("HTTP/1.1 400 Bad Request");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Не указаны необходимые заголовки.");
            return false;
        }
        return true;
    }

    static boolean isContainsContentLengthHeader(int postDataIndex, PrintWriter output) {
        if (postDataIndex == -1) {
            output.println("HTTP/1.1 400 Bad Request");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Не указаны необходимые заголовки.");
            return false;
        }
        return true;
    }

    static String getRootFolder() throws IOException {
        String config = new String(Files.readAllBytes(Paths.get("configuration.txt")));
        List<String> lines = config.lines().collect(Collectors.toList());
        int rootFolderLine = 2;
        return lines.get(rootFolderLine);
    }

    static String getMD5(String text) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(text.getBytes());
        byte[] byteData = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte aByteData : byteData) {
            sb.append(Integer.toString((aByteData & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    static void toGET(PrintWriter output, String path, String ifNonMatchMD5, String ifModifiedSinceData) throws IOException, NoSuchAlgorithmException {
        File fileObject = new File(path);
        if (isFileExists(fileObject, output)) {
            Date date = new Date(fileObject.lastModified());
            String etag = getMD5(date.toString());
            if (etag.equals(ifNonMatchMD5) || date.toString().equals(ifModifiedSinceData)) {
                output.println("HTTP/1.1 304 Not Modified");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("");
            } else {
                if (fileObject.isDirectory()) {
                    if (fileObject.listFiles() == null) {
                        output.println("HTTP/1.1 200 OK");
                        output.println("Content-Type: text/html; charset=utf-8");
                        output.println("Last-Modified: " + date.toString());
                        output.println("Etag: " + etag);
                        output.println("");
                        output.println("Папка пуста.");
                    } else {
                        List<File> fileList = new ArrayList<>();
                        for (File file : Objects.requireNonNull(fileObject.listFiles())) {
                            if (file.isFile() || file.isDirectory())
                                fileList.add(file);
                        }
                        StringBuilder files = new StringBuilder();
                        for (int i = 0; i < fileList.size(); i++) {
                            String line = "<li>" + fileList.get(i).getName() + "</li>";
                            files.append(line);
                            if (i != fileList.size()) {
                                files.append("\n");
                            }
                        }
                        output.println("HTTP/1.1 200 OK");
                        output.println("Content-Type: text/html; charset=utf-8");
                        output.println("Last-Modified: " + date.toString());
                        output.println("Etag: " + etag);
                        output.println("");
                        output.println("<p>Файлы и папки в директории " + fileObject.toString() + ":</p>");
                        output.println("<ul>");
                        output.print(files.toString());
                        output.print("</ul>");
                    }
                } else {
                    String content = new String(Files.readAllBytes(Paths.get(fileObject.toString())));
                    output.println("HTTP/1.1 200 OK");
                    output.println("Content-Type: text/html; charset=utf-8");
                    output.println("Last-Modified: " + date.toString());
                    System.out.println(date.toString());
                    output.println("Etag: " + etag);
                    output.println("");
                    output.println(content);
                }
            }
        }
    }

    static void toPOST(BufferedReader input, PrintWriter output, int postDataIndex, String path) throws IOException {
        char[] charArray = new char[postDataIndex];
        input.read(charArray, 0, postDataIndex);
        String postData = new String(charArray);
        if (postDataIndex > 0) {
            try {
                File file = new File(path);
                if (file.createNewFile()) {
                    Files.write(Paths.get(path), postData.getBytes());
                    output.println("HTTP/1.1 201 Created");
                    output.println("Content-Type: text/html; charset=utf-8");
                    output.println("");
                    output.println("Файл успешно создан.");
                } else {
                    output.println("HTTP/1.1 208 Already Reported");
                    output.println("Content-Type: text/html; charset=utf-8");
                    output.println("");
                    output.println("Такой файл уже существует.");
                }
            } catch (IOException exception) {
                output.println("HTTP/1.1 400 Bad Request");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("");
                output.println("Указана неверная папка для создания файла.");
            }

        } else {
            output.println("HTTP/1.1 400 Bad Request");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Передано пустое содержимое файла.");
        }
    }

    static void toDELETE(PrintWriter output, String path) {
        File file = new File(path);
        if (isFileExists(file, output)) {
            output.println("HTTP/1.1 410 Gone");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("\n");
            if (file.isDirectory()) {
                deleteFolder(file);
                output.println("Папка была удалена.");
            } else {
                file.delete();
                output.println("Файл был удален.");
            }
        }
    }

    static void deleteFolder(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteFolder(f);
            }
        }
        file.delete();
    }

    static boolean isFileExists(File file, PrintWriter output) {
        if (file.exists()) {
            return true;
        } else {
            output.println("HTTP/1.1 404 Not Found");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Файла или папки не существует.");
            return false;
        }
    }
}
