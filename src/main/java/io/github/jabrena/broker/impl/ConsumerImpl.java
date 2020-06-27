package io.github.jabrena.broker.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jabrena.broker.Authentication;
import io.github.jabrena.broker.BrokerClientException;
import io.github.jabrena.broker.BrokerFileParser;
import io.github.jabrena.broker.Consumer;
import io.github.jabrena.broker.GitClientWrapper;
import io.github.jabrena.broker.LocalDirectoryWrapper;
import io.github.jabrena.broker.Message;
import io.github.jabrena.broker.Messages;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;

@Slf4j
@AllArgsConstructor
public class ConsumerImpl<T> implements Consumer<T> {

    private final LocalDirectoryWrapper localRepositoryWrapper;
    private final GitClientWrapper gitWrapper;

    @NonNull
    private final String topic;

    @NonNull
    private final String node;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Constructor
     *
     * @param localRepositoryWrapper localRepositoryWrapper
     * @param gitWrapper gitWrapper
     * @param authentication authentication
     * @param topic application
     * @param node event
     */
    public ConsumerImpl(@NonNull LocalDirectoryWrapper localRepositoryWrapper,
                        @NonNull GitClientWrapper gitWrapper,
                        @NonNull Authentication authentication,
                        @NonNull String topic,
                        @NonNull String node) {

        this.localRepositoryWrapper = localRepositoryWrapper;
        this.gitWrapper = gitWrapper;

        this.topic = topic;
        this.node = node;

        this.gitWrapper.setAuthentication(authentication);
        this.gitWrapper.checkout(this.topic);
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public Message<T> receive() throws BrokerClientException {
        return null;
    }

    private void writeCheckpoint() {

        //Write checkpoint
        final String fileName = this.getFilename("OK");
        LOGGER.info(fileName);
        gitWrapper.addFile(this.localRepositoryWrapper.getLocalFS(), fileName, "PROCESSED");
        gitWrapper.push();
    }

    private String getFilename(String event) {
        return getEpoch() + "_" + this.node + "_" + event + ".json";
    }

    private long getEpoch() {
        return System.currentTimeMillis();
    }

    @Override
    public CompletableFuture<Message<T>> receiveAsync(String event) {
        return null;
    }

    @Override
    public Message<T> receive(int timeout, TimeUnit unit) throws BrokerClientException {
        return null;
    }

    @Override
    public Messages<T> batchReceive() {

        gitWrapper.upgradeRepository(this.topic);

        var localDirectory = this.localRepositoryWrapper.getLocalFS();
        var counter = Arrays.stream(localDirectory.list())
            .filter(y -> y.indexOf(".json") != -1)
            .count();

        //Wait
        if (counter == 0) {
            return emptyMessages();
        } else {

            //Detect last checkpoints
            var checkPointList = Arrays.stream(localDirectory.list())
                .filter(y -> y.indexOf("OK.json") != -1)
                .sorted()
                .collect(toList());

            if (checkPointList.size() > 0) {

                var lastCheckpoint = checkPointList.get(checkPointList.size() - 1);
                var list = Arrays.stream(localDirectory.list())
                    .filter(y -> y.indexOf(".json") != -1)
                    .sorted()
                    .dropWhile(z -> !z.equals(lastCheckpoint))
                    .filter(y -> y.indexOf("OK.json") == -1)
                    .map(BrokerFileParser::new)
                    //.filter(b -> b.getEvent().equals(event))
                    .peek(System.out::println)
                    .collect(toList());

                if (list.size() > 0) {
                    LOGGER.info("Processing messages from last checkpoint: {}", lastCheckpoint);

                    writeCheckpoint();

                    return new Messages<T>() {

                        @Override
                        public int size() {
                            return list.size();
                        }

                        @Override
                        public Iterator<Message<T>> iterator() {
                            return list.stream()
                                .map(x -> (Message<T>) new MessageImpl<T>(x, localRepositoryWrapper))
                                .collect(toUnmodifiableList())
                                .iterator();
                        }

                    };
                } else {
                    LOGGER.info("Without new messages from last checkpoint: {}", lastCheckpoint);
                    return emptyMessages();
                }

            } else if (checkPointList.size() == 0) {

                var count = Arrays.stream(localDirectory.list())
                    .filter(y -> y.indexOf(".json") != -1)
                    .map(BrokerFileParser::new)
                    //.filter(b -> b.getEvent().equals(event))
                    //.peek(System.out::println)
                    .count();

                if (count > 0) {
                    LOGGER.info("Processing messages");

                    var list = Arrays.stream(localDirectory.list())
                        .filter(y -> y.indexOf(".json") != -1)
                        .map(BrokerFileParser::new)
                        .collect(toUnmodifiableList());

                    writeCheckpoint();

                    //Break stream
                    return new Messages<T>() {

                        @Override
                        public int size() {
                            return Long.valueOf(list.stream().count()).intValue();
                        }

                        @Override
                        public Iterator<Message<T>> iterator() {
                            return list.stream()
                                .map(x -> (Message<T>) new MessageImpl<T>(x, localRepositoryWrapper))
                                .collect(toUnmodifiableList())
                                .iterator();
                        }
                    };
                }
                LOGGER.info("Without new messages from last checkpoint: {}", checkPointList);
            }
        }

        return emptyMessages();
    }

    private Messages<T> emptyMessages() {

        return new Messages<T>() {

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Iterator<Message<T>> iterator() {
                return List.of().stream()
                    .map(String::valueOf)
                    .map(BrokerFileParser::new)
                    .map(x -> (Message<T>) new MessageImpl<T>(x, localRepositoryWrapper))
                    .collect(toUnmodifiableList())
                    .iterator();
            }
        };
    }

    @Override
    public void close() throws BrokerClientException {

    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return null;
    }

    @Override
    public boolean hasReachedEndOfTopic() {
        return false;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public String getConsumerName() {
        return null;
    }
}
