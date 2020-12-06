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
            String rootFolder = getRootFolder();
            String request;
            String authLine = "";
            request = input.readLine();
            String line;
            String authHeader = "Authorization: ";
            String contentHeader = "Content-Length:";
            int postDataIndex = -1;
            while ((line = input.readLine()) != null && (line.length() != 0)) {
                if (line.contains(authHeader)) {
                    authLine = line.substring(authHeader.length());
                } else if (line.contains(contentHeader)) {
                    postDataIndex = Integer.parseInt(line.substring(contentHeader.length() + 1));
                }
            }
            if (request == null) {
                output.println("HTTP/1.1 400 Bad Request");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("");
                output.println("Отправлен неверный запрос.");
            } else {
                int endIndexOfRequest = request.indexOf("HTTP") - 1;
                String clippedRequest = request.substring(0, endIndexOfRequest);
                String[] requestMethods = {"GET", "POST", "DELETE"};
                for (String requestMethod : requestMethods) {
                    if (request.contains(requestMethod)) {
                        String replacement = rootFolder + clippedRequest.substring(requestMethod.length() + 1);
                        String path = replacement.replace("/", "\\");
                        System.out.println(path);
                        if (isAuth(authLine)) {
                            switch (requestMethod) {
                                case "GET" -> toGET(output, path);
                                case "POST" -> toPOST(input, output, postDataIndex, path);
                                case "DELETE" -> toDELETE(output, path);
                            }
                        } else {
                            output.println("HTTP/1.1 401 Unauthorized");
                            output.println("Content-Type: text/html; charset=utf-8");
                            output.println("");
                            output.println("Логин или пароль неверны.");
                        }
                    }
                }
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

    static void toGET(PrintWriter output, String path) throws IOException, NoSuchAlgorithmException {
        File fileObject = new File(path);
        if (fileObject.exists()) {
            Date date = new Date(fileObject.lastModified());
            String etag = getMD5(date.toString());
            if (fileObject.isDirectory()) {
                List<File> fileList = new ArrayList<>();
                if (fileObject.listFiles() == null) {
                    output.println("HTTP/1.1 204 No Content");
                    output.println("Content-Type: text/html; charset=utf-8");
                    output.println("Last-Modified: " + date.toString());
                    output.println("Etag: " + etag);
                    output.println("");
                    output.println("Папка пуста.");
                } else {
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
            } else if (fileObject.isFile()) {
                String content = new String(Files.readAllBytes(Paths.get(fileObject.toString())));
                output.println("HTTP/1.1 200 OK");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("Last-Modified: " + date.toString());
                output.println("Etag: " + etag);
                output.println("");
                output.println(content);
            }
        } else {
            output.println("HTTP/1.1 404 Not Found");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Файла или папки не существует.");
        }

    }

    static void toPOST(BufferedReader input, PrintWriter output, int postDataIndex, String path) throws IOException {
        if (postDataIndex > 0) {
            char[] charArray = new char[postDataIndex];
            input.read(charArray, 0, postDataIndex);
            String postData = new String(charArray);
            File file = new File(path);
            String folderPath = path.substring(0, path.lastIndexOf("\\"));
            File folder = new File(folderPath);
            if (!folder.exists()) {
                folder.mkdir();
            }
            if (file.createNewFile()) {
                Files.write(Paths.get(path), postData.getBytes());
                output.println("HTTP/1.0 201 Created");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("");
                output.println("Файл успешно создан.");
            } else {
                output.println("HTTP/1.0 208 Already Reported");
                output.println("Content-Type: text/html; charset=utf-8");
                output.println("");
                output.println("Такой файл уже существует.");
            }
        } else {
            output.println("HTTP/1.0 204 No Content");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Передано пустое содержимое файла.");
        }
    }

    static void toDELETE(PrintWriter output, String path) {
        File file = new File(path);
        if (file.exists()) {
            output.write("HTTP/1.1 410 Gone\n");
            output.write("Content-Type: text/html; charset=utf-8\n");
            output.write("\n");
            if (file.isDirectory()) {
                deleteFolder(file);
                output.write("<p>Папка была удалена</p>\n");
            } else {
                file.delete();
                output.write("<p>Файл был удален</p>\n");
            }
        } else {
            output.println("HTTP/1.1 404 Not Found");
            output.println("Content-Type: text/html; charset=utf-8");
            output.println("");
            output.println("Файла или папки не существует.");
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

    boolean isAuth(String authLine) throws IOException {
        String login = authLine.substring(0, authLine.indexOf(','));
        String password = authLine.substring(authLine.indexOf(',') + 1);
        String config = new String(Files.readAllBytes(Paths.get("configuration.txt")));
        List<String> lines = config.lines().collect(Collectors.toList());
        return lines.get(0).equals(login) && lines.get(1).equals(password);
    }

    String getRootFolder() throws IOException {
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
}
