import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ActionsFile {
    private static String ABSOLUTE_SERVER_PATH;
    private static Map<SocketAddress, Path> roots = new HashMap<>();
    private static Map<SocketAddress, String> currentPaths = new HashMap<>();
    public ActionsFile() {
        ABSOLUTE_SERVER_PATH = null;
    }

    static String createFile(String command, String currentPath, SocketAddress client) {
        String[] filename = command.split(" ", 2);
        Path path = Paths.get("server", currentPath, filename[1]);
        try {
            if (path.toAbsolutePath().normalize().startsWith(Path.of(ABSOLUTE_SERVER_PATH, roots.get(client).toString()))) {
                if (Files.notExists(path)) {
                    Files.createFile(path);
                    return "File " + filename[1] + " created\n\r";
                } else {
                    return "File already exists\n\r";
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    //создание директории
    static String makeDirectory(String command, String currentPath, SocketAddress client) {
        String[] directory = command.split(" ", 2);
        String pathDir = directory[1].trim();
        Path path = Paths.get("server", currentPath, pathDir);
        try {
            // создание корневой дирректории для нового клиента
            if ("server".equals(currentPath)) {
                path = Paths.get(currentPath, pathDir);
                if (Files.notExists(path)) {
                    Files.createDirectory(path);
                }
                return "";
            }
            if (path.toAbsolutePath().normalize().startsWith(Path.of(ABSOLUTE_SERVER_PATH, roots.get(client).toString()))) {
                if (Files.notExists(path)) {
                    Files.createDirectory(path);
                    return "Directory " + pathDir + " created\n\r";
                } else {
                    return "Directory already exists!\n\r";
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "\n\r";
        }
        return "\n\r";
    }

    // Удаление файла / директории
    static String remove(String command, String currentPath, SocketAddress client) {

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
                if (target.toAbsolutePath().normalize().equals(Path.of(currentPath).toAbsolutePath().normalize())) {
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
    static String copy(String command, String currentPath, SocketAddress client) {
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
    private static void copyDirectory(Path sourcePath, Path targetPath) {
        try {
            Files.walkFileTree(sourcePath, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = targetPath.resolve(sourcePath.relativize(dir));
                    if (Files.notExists(target)) {
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
    static String newDirectory(String command, String currentPath, SocketAddress client) {
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
    static void viewFile(String command, Selector selector, SocketAddress client) throws IOException {
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

    static void sendMessage(String message, Selector selector, SocketAddress client) throws IOException {
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                if (((SocketChannel) key.channel()).getRemoteAddress().equals(client)) {
                    ((SocketChannel) key.channel()).write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }


    // получение списка файлов и папок в текущей директории
    static String getFilesList(String currentPath) {
        String[] servers = new File("server" + File.separator + currentPath).list();
        if (!(servers == null) && servers.length > 0) {
            Arrays.sort(servers);
            return String.join(" ", servers).concat("\n\r");
        } else {
            return "\n\r";
        }
    }
}