package com.filesharingsystem.controller;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import java.util.logging.Logger;

import com.filesharingsystem.model.*;
import com.filesharingsystem.service.DBService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class FileController {
    // Dependency Injection of DBService and Factory for creating users
    @Autowired
    private DBService fileService;
    private UserFactory userFactory = new UserFactory();

    // Components for file sharing system
    private Room room;
    private Client client;
    private Peer peer;
    private boolean isClient;

    // Main REST API ports
    private String mainPort;
    private String mainIP;

    // Connection port/IP for clients
    private String clientLink;

    // Cryptographic utility
    private Crypto crypto;

    // Exception handling advice
    @ControllerAdvice
    public class GlobalExceptionHandler {
        private static final Logger logger = Logger.getLogger(GlobalExceptionHandler.class.getName());

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public String handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, Model model) {
            // Handling maximum upload size exceeded exception
            String errorMessage = "Maximum file size exceeded. Please upload a file smaller than 1 MB.";
            logger.severe(errorMessage); // Log the error message at SEVERE level
            model.addAttribute("errorMessage", errorMessage); // Add error message to the model
            return "error"; // Return the name of the error page view
        }
    }

    // Method to register client/peer (Builder Pattern)
    private String registerMyself(String myself, String link) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(link + "/register/" + myself))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    // Method to get all peers from client
    private List<String> getAllPeers() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(clientLink + "/getAllPeersFromClient"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Parsing response to extract peers
        String peerListString = response.body().substring(1, response.body().length() - 1);
        String[] peerArray = peerListString.split(", ");
        List<String> peerList = new ArrayList<>(Arrays.asList(peerArray));
        return peerList;
    }

    // Register peer method
    @GetMapping("/register/{peer}")
    @ResponseBody
    public String register(@PathVariable String peer) {
        if (room == null) {
            System.out.println("No room created"); // Log message indicating no room created
            return "No room created"; // Return message to the client
        }

        room.addPeers(peer);
        System.out.println(room.getPeers());
        return room.getKey();
    }

    // Get all peers from client method
    @GetMapping("/getAllPeersFromClient")
    @ResponseBody
    public String getAllPeersFromClientFunc() {
        return room.getPeers().toString();
    }

    // Method to send file
    @PostMapping("/send/{id}")
    public RedirectView sendFileFunc(@PathVariable String id) throws Exception {
        myFile file = fileService.getFile(id);
        SendFile s = SendFile.getInstance();
        List<String> peers = getAllPeers();
        String myself = mainIP + ":" + mainPort;

        String srcDataIP, srcDataPort;
        if (isClient) {
            srcDataIP = client.getIP();
            srcDataPort = client.getPort();
        } else {
            srcDataIP = peer.getIP();
            srcDataPort = peer.getPort();
        }

        // Writing file to temporary location
        String filepath = System.getProperty("java.io.tmpdir") + "/tmp";
        File tmpFile = new File(filepath);
        OutputStream os = new FileOutputStream(tmpFile);
        os.write(file.getFile());
        os.close();

        FileInputStream fl = new FileInputStream(tmpFile);
        byte[] fileBuffer = new byte[(int) tmpFile.length()];
        fl.read(fileBuffer);
        fl.close();

        String checksum = crypto.calculateChecksum(fileBuffer);

        String params = "IP=" + srcDataIP + "&port=" + srcDataPort + "&name=" + file.getFilename() + "&type=" + file.getFileType() + "&checksum=" + checksum;

        // Sending file to each peer
        for (String peer_ : peers) {
            if (!peer_.equals(myself)) {

                HttpClient client_ = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + peer_ + "/receiveFile?" + params))
                        .build();

                client_.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(response -> {
                            System.out.println(response.statusCode());
                            return response;
                        })
                        .thenApply(HttpResponse::body)
                        .thenAccept(System.out::println);


                if (isClient)
                    s.send(tmpFile, client.getIP(), Integer.parseInt(client.getPort()), crypto);
                else
                    s.send(tmpFile, peer.getIP(), Integer.parseInt(peer.getPort()), crypto);

            }
        }

        // Redirecting to show all files page
        RedirectView redirectView = new RedirectView();
        redirectView.setUrl("http://" + mainIP + ":" + mainPort + "/showAllFiles");
        redirectView.setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
        return redirectView;
    }

    // Method to receive file
    @GetMapping("/receiveFile")
    public RedirectView receiveFileFunc(@RequestParam String IP, @RequestParam String port, @RequestParam String name, @RequestParam String type, @RequestParam String checksum) throws Exception {
        RedirectView redirectView = new RedirectView();
        redirectView.setUrl("http://" + mainIP + ":" + mainPort + "/showAllFiles");
        redirectView.setStatusCode(HttpStatus.TEMPORARY_REDIRECT);

        ReceiveFile r = ReceiveFile.getInstance();
        if (r == null) {
            return redirectView;
        }
        myFile output = r.receive(IP, Integer.parseInt(port), crypto, name, type, checksum);
        CustomMultipartFile custom = new CustomMultipartFile(output.getFile(), output.getFilename(), output.getFileType(), (long) Integer.parseInt(output.getFileSize()));
        fileService.addFileCustom(custom);
        return redirectView;
    }

    // Method to create room
    @PostMapping("/createRoom")
    public String createRoomFunc(HttpServletRequest request, Model model, String nickname) throws JsonProcessingException, IOException, InterruptedException {

        if (!nickname.matches("^[A-Za-z]+$")) {
            // Nickname contains invalid characters, return to a page indicating the error
            return "error1";
        }

        // Delete all existing files
        fileService.deleteAll();
        isClient = true;

        // Construct client link
        clientLink = request.getRequestURL().toString().split("/")[0] + "//" + request.getRequestURL().toString().split("/")[2];
        client = (Client) userFactory.getObject(2, nickname, clientLink);
        client = new Client(nickname, clientLink);
        room = client.getRoom();
        peer = null;
        mainIP = request.getLocalName();
        mainPort = "" + request.getLocalPort();
        crypto = new Crypto(room.getKey());
        room.addPeers(mainIP + ":" + mainPort);

        // Open the upload.html file
        return "upload";
    }

    // Method to join room
    @PostMapping("/joinRoom")
    public String joinRoomFunc(HttpServletRequest request, Model model, String nickname, String link) throws IOException, InterruptedException {
        if (!nickname.matches("^[A-Za-z]+$")) {
            // Nickname contains invalid characters, return to a page indicating the error
            return "error2";
        }

        if (!link.equals("http://localhost:8002")) {
            // Nickname contains invalid characters, return to a page indicating the error
            return "error2";
        }

        // Valid nickname and link, proceed with joining the room
        fileService.deleteAll();
        clientLink = link;
        isClient = false;
        room = null;
        peer = (Peer) userFactory.getObject(1, nickname, null);
        client = null;
        mainIP = request.getLocalName();
        mainPort = "" + request.getLocalPort();
        String key_ = registerMyself(mainIP + ":" + mainPort, link);
        crypto = new Crypto(key_);

        // Open the upload.html file
        return "upload";
    }

    // Method to upload file
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        // Upload file to storage
        String id = fileService.addFile(file);
        return "upload";
    }

    // Method to get all files
    @GetMapping("/getFiles")
    public ResponseEntity<?> getFilesRoute() throws IOException {
        return new ResponseEntity<>(fileService.getFiles().toString(), HttpStatus.OK);
    }

    // Method to delete file
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable String id) throws IOException {
        fileService.deleteFile(id);
        return "upload";
    }

    // Method to show all files
    @GetMapping("/showAllFiles")
    public String showAllFiles() {
        return "upload";
    }

    // Method to handle POST request for showing all files
    @PostMapping("/showAllFiles")
    public String showAllFilesPost() {
        return "upload";
    }

    // Method to download file
    @PostMapping("/download/{id}")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) throws IOException {
        myFile loadFile = fileService.downloadFile(id);

        // Send file as response
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loadFile.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + loadFile.getFilename() + "\"")
                .body(new ByteArrayResource(loadFile.getFile()));
    }

}
