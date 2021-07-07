import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

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


        //Проверяем, существует ли пользователь, если нет сохраняем в Мар
        String userName;
        if(clients.get(channel.getRemoteAddress()) == null){
            // добавить имя клиента
            userName = "user" + userNumber++;
            clients.put(channel.getRemoteAddress(),userName);
            //создаем сразу директорию под нового клиента
            ActionsFile.makeDirectory("mkdir " + userName,"server",channel.getRemoteAddress());
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


        // TODO: 21.06.2021

        // changenick (nickname) - изменение имени пользователя
        // добавить имя клиента


        if (key.isValid()) {
            String command = sb.toString()
                    .replace("\n", "")
                    .replace("\r", "");
            if ("--help".equals(command)) {
                for (String c : COMMANDS){
                    ActionsFile.sendMessage(c, selector, client);
                }
            } else if ("ls".equals(command)) {
                ActionsFile.sendMessage(ActionsFile.getFilesList(currentPath).concat("\n\r"), selector, client);
            }// touch (filename) - создание файла
            else if (command.startsWith("touch ")) {
               ActionsFile.sendMessage(ActionsFile.createFile(command, currentPath, client).concat("\n\r"), selector, client);
            } // mkdir (dirname) - создание директории
            else if(command.startsWith("mkdir ")){
                ActionsFile.sendMessage(ActionsFile.makeDirectory(command, currentPath, client).concat("\n\r"), selector, client);
            } // rm (filename / dirname) - удаление файла / директории
            else if(command.startsWith("rm ")){
                ActionsFile.sendMessage(ActionsFile.remove(command, currentPath, client).concat("\n\r"), selector, client);
            } // copy (src) (target) - копирование файлов / директории
            else if(command.startsWith("copy ")){
                ActionsFile.sendMessage(ActionsFile.copy(command, currentPath, client).concat("\n\r"), selector, client);
            }  // cd (path | ~ | ..) - изменение текущего положения
            else if(command.startsWith("cd ")){
                ActionsFile.sendMessage(ActionsFile.newDirectory(command, currentPath, client).concat("\n\r"), selector, client);
            } // cat (filename) - вывод содержимого текстового файла
            else if(command.startsWith("cat ")){
                ActionsFile.viewFile(command, selector, client);
            }
        }
        String startOfLine = userName + ": " + currentPaths.get(client) + "> ";
        ActionsFile.sendMessage(startOfLine, selector, client);
    }





    public static void main(String[] args) throws Exception {
        new NioTelnetServer();
    }
}