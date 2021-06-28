import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class NioTelnetServer {
    private static final String[] COMMANDS = {
            "\tls         view all files from current directory\n\r",
            "\ttouch      create new file\n\r",
            "\tmkdir      create new directory\n\r",
            "\tcd         (path | ~ | ..) change current directory to path, to root or one level up\n\r",
            "\trm         (filename / dirname) remove file / directory\n\r",
            "\tcopy       (src) (target) copy file or directory from src path to target path\n\r",
            "\tcat        (filename) view text file\n\r",
            "\tchangenick (nickname) change user's nickname\n\r"
    };

    private final ByteBuffer buffer = ByteBuffer.allocate(512);
    private static int userNumber;
    private Map<SocketAddress, String> clients = new HashMap<>();
    private Map<SocketAddress, String> currentPaths = new HashMap<>();
    private Map<SocketAddress, Path> roots = new HashMap<>();

    private final String ABSOLUTE_SERVER_PATH;

    public NioTelnetServer() throws Exception {
        userNumber=1;//начальный номер отсчета клиентов
        ABSOLUTE_SERVER_PATH = Paths.get("server").toAbsolutePath().toString();//общий путь для все клиентов
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(5679));
        server.configureBlocking(false);
        Selector selector = Selector.open();

        server.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("Server started");
        while (server.isOpen()) {
            selector.select(); //ожидание команды от клиента
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                if (key.isAcceptable()) {
                    handleAccept(key, selector);
                } else if (key.isReadable()) {
                    handleRead(key, selector);
                }
                iterator.remove();
            }
        }
    }

    private void handleAccept(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
        channel.configureBlocking(false);
        System.out.println("Client connected. IP:" + channel.getRemoteAddress());
        channel.register(selector, SelectionKey.OP_READ, "skjghksdhg");
      /*  channel.write(ByteBuffer.wrap("Hello user!\n".getBytes(StandardCharsets.UTF_8)));
        channel.write(ByteBuffer.wrap("Enter --help for support info\n".getBytes(StandardCharsets.UTF_8)));
      */
        //Проверяем, существует ли пользователь, если нет сохраняем в Мар
        String userName;
        if(clients.get(channel.getRemoteAddress()) == null){
            // добавить имя клиента
            userName = "user" + userNumber++;
            clients.put(channel.getRemoteAddress(),userName);
            //создаем сразу директорию под нового клиента
            makeDirectory("mkdir " + userName,"server",channel.getRemoteAddress());
        }else {
            userName = clients.get(channel.getRemoteAddress());
        }
        currentPaths.putIfAbsent(channel.getRemoteAddress(),userName);
        roots.putIfAbsent(channel.getRemoteAddress(),Paths.get(userName));//дирректория текущего пользователя
        channel.write(ByteBuffer.wrap(String.join(" ","Hello",userName,"\n\r").getBytes(StandardCharsets.UTF_8)));
        channel.write(ByteBuffer.wrap("Enter --help for support info\n\r".getBytes(StandardCharsets.UTF_8)));
    }


    private void handleRead(SelectionKey key, Selector selector) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        SocketAddress client = channel.getRemoteAddress();
        String currentPath = currentPaths.get(client);
        String userName = clients.get(client);
        int readBytes = channel.read(buffer);

        if (readBytes < 0) {
            channel.close();
            return;
        } else  if (readBytes == 0) {
            return;
        }

        buffer.flip();
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            sb.append((char) buffer.get());
        }
        buffer.clear();

/*        String root = "server";
        Path path = Path.of("");
        WatchService service = FileSystems.getDefault().newWatchService();
        path.register(service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
*/
        // TODO: 21.06.2021


        // changenick (nickname) - изменение имени пользователя



        if (key.isValid()) {
            String command = sb.toString()
                    .replace("\n", "")
                    .replace("\r", "");
            if ("--help".equals(command)) {
                for (String c : COMMANDS){
                    sendMessage(c, selector, client);
                }
            } else if ("ls".equals(command)) {
                sendMessage(getFilesList(currentPath).concat("\n\r"), selector, client);
            }// touch (filename) - создание файла
            else if (command.startsWith("touch ")) {
                sendMessage(createFile(command, currentPath, client).concat("\n\r"), selector, client);
            } // mkdir (dirname) - создание директории
            else if(command.startsWith("mkdir ")){
                sendMessage(makeDirectory(command, currentPath, client).concat("\n\r"), selector, client);
            } // rm (filename / dirname) - удаление файла / директории
            else if(command.startsWith("rm ")){
                sendMessage(remove(command, currentPath, client).concat("\n\r"), selector, client);
            } // copy (src) (target) - копирование файлов / директории
            else if(command.startsWith("copy ")){
                sendMessage(copy(command, currentPath, client).concat("\n\r"), selector, client);
            }  // cd (path | ~ | ..) - изменение текущего положения
            else if(command.startsWith("cd ")){
                sendMessage(newDirectory(command, currentPath, client).concat("\n\r"), selector, client);
            } // cat (filename) - вывод содержимого текстового файла
            else if(command.startsWith("cat ")){
                viewFile(command, selector, client);
            }
        }
        String startOfLine = userName + ": " + currentPaths.get(client) + "> ";
        sendMessage(startOfLine, selector, client);
    }

    private String createFile(String command, String currentPath, SocketAddress client){
        String[] filename = command.split(" ",2);
        Path path = Paths.get("server", currentPath,filename[1]);
        try {
            if (path.toAbsolutePath().normalize().startsWith(Path.of(ABSOLUTE_SERVER_PATH,roots.get(client).toString()))){
                if(Files.notExists(path)){
                    Files.createFile(path);
                    return "File " + filename[1] + " created\n\r";
                }else {
                    return "File already exists\n\r";
                }
            }
        } catch (IOException e){
            e.printStackTrace();
        }
        return "";
    }

    //создание директории
    private String makeDirectory(String command, String currentPath,SocketAddress client){
        String[] directory = command.split(" ",2);
        String pathDir = directory[1].trim();
        Path path = Paths.get("server",currentPath, pathDir);
        try {
            // создание корневой дирректории для нового клиента
            if("server".equals(currentPath)){
                path = Paths.get(currentPath,pathDir);
                if(Files.notExists(path)){
                    Files.createDirectory(path);
                }
                return "";
            }
            if(path.toAbsolutePath().normalize().startsWith(Path.of(ABSOLUTE_SERVER_PATH,roots.get(client).toString()))){
               if(Files.notExists(path)){
                   Files.createDirectory(path);
                   return "Directory " + pathDir + " created\n\r";
               } else {
                   return "Directory already exists!\n\r";
               }
            }
        }catch (IOException e){
            e.printStackTrace();
            return "\n\r";
        }
        return "\n\r";
    }

    // Удаление файла / директории
    private String remove(String command, String currentPath, SocketAddress client) {
        String msg = "";
        String[] arguments = command.split(" ", 2);
        if (arguments.length < 2) {
            return msg;
        }
        String path = arguments[1].trim();
        Path target = null;
        if ("".equals(path)) {
            return msg;
        }
        String root = roots.get(client).toString();
        Path absoluteRoot = Path.of(ABSOLUTE_SERVER_PATH, root);

        try {
            // Если path находится в директории данного пользователя
            if (Path.of("server", currentPath, path).toAbsolutePath().normalize().startsWith(absoluteRoot)) {
                // Если path представляет путь относительно текущей директории и существует
                if (Files.exists(Path.of("server", currentPath, path))) {
                    // Изменяем target на currentPath/path
                    target = Path.of("server", currentPath, path);
                    // Если path представляет полный путь относительно директории server и существует
                }
                if (Files.exists(Path.of("server", path))) {
                    // Изменяем target на path
                    target = Path.of("server", path);
                }
            } else {
                return "Path not found";
            }
            // Если path указывает на директорию и она не является корневой
            if (Files.isDirectory(target) && !target.toAbsolutePath().normalize().equals(absoluteRoot)) {
                // Проходимся по всем вложенным директориям и удаляем все вложенные файлы и директории
                Files.walkFileTree(target, new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                if (target.toAbsolutePath().normalize().equals(Path.of(currentPath).toAbsolutePath().normalize())){
                    currentPaths.replace(client, target.getParent().relativize(Path.of("server")).toString());
                }
                return Files.exists(target) ? "Something wrong" : "Directory deleted";
            } else {
                // Если path указывает на файл то удаляем его
                return Files.deleteIfExists(target) ? "File deleted" : "Delete filed";
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    // копирование файлов / директории
    private String copy(String command, String currentPath, SocketAddress client) {
        String root = roots.get(client).toString();
        String[] arguments = command.split(" ", 3);
        if (arguments.length < 3) {
            return "";
        }
        Path source = Path.of("server", root, arguments[1].trim());
        Path target = Path.of("server", root, arguments[2].trim());

        if ("".equals(source.toString()) || "".equals(target.toString())) {
            return "";
        }
        Path absoluteRoot = Path.of(ABSOLUTE_SERVER_PATH, root);

        try {
            // Если source и target находятся в директории данного пользователя
            if (source.toAbsolutePath().normalize().startsWith(absoluteRoot) && target.toAbsolutePath().normalize().startsWith(absoluteRoot)) {
                // Если source представляет путь относительно корневого каталога пользователя и существует
                if (Files.exists(source)) {
                    // Если source является директорией
                    if (Files.isDirectory(source)) {
                        // Копируем source в target
                        copyDirectory(source, target);
                        return "Directory copied";

                        // Если source является файлом
                    } else {
                        // Копируем source в target
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        return "File copied";
                    }
                } else {
                    throw new IOException("Source not found");
                }
            } else {
                throw new IOException("Path not found");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return e.getMessage();
        }
    }

    // копирование директории
    private void copyDirectory(Path sourcePath, Path targetPath) {
        try {
            Files.walkFileTree(sourcePath, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = targetPath.resolve(sourcePath.relativize(dir));
                    if(Files.notExists(target)){
                        Files.createDirectory(target);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, targetPath.resolve(sourcePath.relativize(file)),
                            StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // изменение текущей директории
    private String newDirectory(String command, String currentPath, SocketAddress client) {
        String msg = "";
        String[] arguments = command.split(" ", 2);
        if (arguments.length < 2) {
            return msg;
        }
        String path = arguments[1].trim();
        if ("".equals(path)) {
            return msg;
        }
        String root = roots.get(client).toString();
        Path absoluteRoot = Path.of(ABSOLUTE_SERVER_PATH, root);

        try {
            if ("~".equals(path)) {
                currentPath = root;
            } else if ("..".equals(path)) {
                if (Paths.get("server", currentPath).getParent().toRealPath().startsWith(absoluteRoot)) {
                    currentPath = Paths.get(currentPath).getParent().normalize().toString();
                }
            } else {
                Path normalizedPath = Paths.get("server", root, path).toAbsolutePath().normalize();
                // Если path находится в ветке данного пользователя
                if (normalizedPath.startsWith(absoluteRoot)) {
                    // Если path представляет полный путь относительно директории server, существует и является директорией
                    if (Files.exists(Paths.get("server", path)) && Files.isDirectory(Paths.get("server", path))) {
                        // Изменяем текущую директорию на path
                        currentPath = path;
                        // Если path представляет путь относительно текущей директории, существует и является директорией
                    } else if (Files.exists(Paths.get("server", currentPath, path)) && Files.isDirectory(Paths.get("server", currentPath,
                            path))) {
                        // Изменяем текущую директорию на currentPath/path
                        currentPath = Paths.get(currentPath, path).toString();
                    }
                } else {
                    msg = "Wrong path!\n\r";
                }
            }
            currentPaths.replace(client, currentPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return msg;
    }

    // Просмотр текстовых файлов
    private void viewFile(String command, Selector selector, SocketAddress client) throws IOException {
        String[] arguments = command.split(" ", 2);
        if (arguments.length < 2) {
            return;
        }
        Path filePath = Path.of("server", currentPaths.get(client), arguments[1]);
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                if (((SocketChannel) key.channel()).getRemoteAddress().equals(client)) {
                    ((SocketChannel) key.channel()).write(ByteBuffer.wrap(Files.readAllBytes(filePath)));
                    ((SocketChannel) key.channel()).write(ByteBuffer.wrap("\n\n\r".getBytes()));
                }
            }
        }
    }

    private void sendMessage(String message, Selector selector, SocketAddress client) throws IOException {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                if (((SocketChannel) key.channel()).getRemoteAddress().equals(client)) {
                    ((SocketChannel) key.channel()).write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

// получение списка файлов и папок в текущей директории
    private String getFilesList(String currentPath) {
        String[] servers = new File("server" + File.separator + currentPath).list();
        if(!(servers == null) && servers.length > 0){
            Arrays.sort(servers);
            return String.join(" ", servers).concat("\n\r");
        }else {
            return "\n\r";
        }
    }



    public static void main(String[] args) throws Exception {
        new NioTelnetServer();
    }
}
