/*
 * Some portion .. Copyright (c) 2014 CIYAM Developers

 Distributed under the MIT/X11 software license, please refer to the file LICENSE.txt
*/

package brs.at;


import brs.*;
import brs.db.EsgKey;
import brs.db.TransactionDb;
import brs.db.VersionedEntityTable;
import brs.services.AccountService;
import brs.util.Listener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class AT extends AtMachineState {

    private static final LinkedHashMap<Long, Long> pendingFees = new LinkedHashMap<>();
    private static final List<AtTransaction> pendingTransactions = new ArrayList<>();
    public final EsgKey dbKey;
    private final String name;
    private final String description;
    private final int nextHeight;

    private AT(byte[] atId, byte[] creator, String name, String description, byte[] creationBytes, int height) {
        super(atId, creator, creationBytes, height);
        this.name = name;
        this.description = description;
        dbKey = atDbKeyFactory().newKey(AtApiHelper.getLong(atId));
        this.nextHeight = Esg.getBlockchain().getHeight();
    }

    public AT(byte[] atId, byte[] creator, String name, String description, short version,
              byte[] stateBytes, int csize, int dsize, int cUserStackBytes, int cCallStackBytes,
              int creationBlockHeight, int sleepBetween, int nextHeight,
              boolean freezeWhenSameBalance, long minActivationAmount, byte[] apCode) {
        super(atId, creator, version,
                stateBytes, csize, dsize, cUserStackBytes, cCallStackBytes,
                creationBlockHeight, sleepBetween,
                freezeWhenSameBalance, minActivationAmount, apCode);
        this.name = name;
        this.description = description;
        dbKey = atDbKeyFactory().newKey(AtApiHelper.getLong(atId));
        this.nextHeight = nextHeight;
    }

    public static void clearPendingFees() {
        pendingFees.clear();
    }

    public static void clearPendingTransactions() {
        pendingTransactions.clear();
    }

    public static void addPendingFee(long id, long fee) {
        pendingFees.put(id, fee);
    }

    public static void addPendingFee(byte[] id, long fee) {
        addPendingFee(AtApiHelper.getLong(id), fee);
    }

    public static void addPendingTransaction(AtTransaction atTransaction) {
        pendingTransactions.add(atTransaction);
    }

    public static boolean findPendingTransaction(byte[] recipientId) {
        for (AtTransaction tx : pendingTransactions) {
            if (Arrays.equals(recipientId, tx.getRecipientId())) {
                return true;
            }
        }
        return false;
    }

    private static EsgKey.LongKeyFactory<AT> atDbKeyFactory() {
        return Esg.getStores().getAtStore().getAtDbKeyFactory();
    }

    private static VersionedEntityTable<AT> atTable() {
        return Esg.getStores().getAtStore().getAtTable();
    }

    private static EsgKey.LongKeyFactory<ATState> atStateDbKeyFactory() {
        return Esg.getStores().getAtStore().getAtStateDbKeyFactory();
    }

    private static VersionedEntityTable<ATState> atStateTable() {
        return Esg.getStores().getAtStore().getAtStateTable();
    }

    public static AT getAT(byte[] id) {
        return getAT(AtApiHelper.getLong(id));
    }

    public static AT getAT(Long id) {
        return Esg.getStores().getAtStore().getAT(id);
    }

    public static void addAT(Long atId, Long senderAccountId, String name, String description, byte[] creationBytes, int height) {
        ByteBuffer bf = ByteBuffer.allocate(8 + 8);
        bf.order(ByteOrder.LITTLE_ENDIAN);

        bf.putLong(atId);

        byte[] id = new byte[8];

        bf.putLong(8, senderAccountId);

        byte[] creator = new byte[8];
        bf.clear();
        bf.get(id, 0, 8);
        bf.get(creator, 0, 8);

        AT at = new AT(id, creator, name, description, creationBytes, height);

        AtController.resetMachine(at);

        atTable().insert(at);

        at.saveState();

        Account account = Account.getOrAddAccount(atId);
        account.apply(new byte[32], height);
    }

    public static List<Long> getOrderedATs() {
        return Esg.getStores().getAtStore().getOrderedATs();
    }

    public static byte[] compressState(byte[] stateBytes) {
        if (stateBytes == null || stateBytes.length == 0) {
            return null;
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(stateBytes);
                gzip.flush();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static byte[] decompressState(byte[] stateBytes) {
        if (stateBytes == null || stateBytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bis = new ByteArrayInputStream(stateBytes);
             GZIPInputStream gzip = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = gzip.read(buffer, 0, buffer.length)) > 0) {
                bos.write(buffer, 0, read);
            }
            bos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void saveState() {
        ATState state = atStateTable().get(atStateDbKeyFactory().newKey(AtApiHelper.getLong(this.getId())));
        int prevHeight = Esg.getBlockchain().getHeight();
        int newNextHeight = prevHeight + getWaitForNumberOfBlocks();
        if (state != null) {
            state.setState(getState());
            state.setPrevHeight(prevHeight);
            state.setNextHeight(newNextHeight);
            state.setSleepBetween(getSleepBetween());
            state.setPrevBalance(getpBalance());
            state.setFreezeWhenSameBalance(freezeOnSameBalance());
            state.setMinActivationAmount(minActivationAmount());
        } else {
            state = new ATState(AtApiHelper.getLong(this.getId()),
                    getState(), newNextHeight, getSleepBetween(),
                    getpBalance(), freezeOnSameBalance(), minActivationAmount());
        }
        atStateTable().insert(state);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int nextHeight() {
        return nextHeight;
    }

    public static class HandleATBlockTransactionsListener implements Listener<Block> {
        private final AccountService accountService;
        private final Blockchain blockchain;
        private final TransactionDb transactionDb;

        public HandleATBlockTransactionsListener(AccountService accountService, Blockchain blockchain, TransactionDb transactionDb) {
            this.accountService = accountService;
            this.blockchain = blockchain;
            this.transactionDb = transactionDb;
        }

        @Override
        public void notify(Block block) {
            pendingFees.forEach((key, value) -> {
                Account atAccount = accountService.getAccount(key);
                accountService.addToBalanceAndUnconfirmedBalanceNQT(atAccount, -value);
            });

            List<Transaction> transactions = new ArrayList<>();
            for (AtTransaction atTransaction : pendingTransactions) {
                accountService.addToBalanceAndUnconfirmedBalanceNQT(accountService.getAccount(AtApiHelper.getLong(atTransaction.getSenderId())), -atTransaction.getAmount());
                accountService.addToBalanceAndUnconfirmedBalanceNQT(accountService.getOrAddAccount(AtApiHelper.getLong(atTransaction.getRecipientId())), atTransaction.getAmount());

                Transaction.Builder builder = new Transaction.Builder((byte) 1, Genesis.getCreatorPublicKey(),
                        atTransaction.getAmount(), 0L, block.getTimestamp(), (short) 1440, Attachment.AT_PAYMENT);

                builder.senderId(AtApiHelper.getLong(atTransaction.getSenderId()))
                        .recipientId(AtApiHelper.getLong(atTransaction.getRecipientId()))
                        .blockId(block.getId())
                        .height(block.getHeight())
                        .blockTimestamp(block.getTimestamp())
                        .ecBlockHeight(0)
                        .ecBlockId(0L);

                byte[] message = atTransaction.getMessage();
                if (message != null) {
                    builder.message(new Appendix.Message(message, blockchain.getHeight()));
                }

                try {
                    Transaction transaction = builder.build();
                    if (!transactionDb.hasTransaction(transaction.getId())) {
                        transactions.add(transaction);
                    }
                } catch (EsgException.NotValidException e) {
                    throw new RuntimeException("Failed to construct AT payment transaction", e);
                }
            }

            if (!transactions.isEmpty()) {
                // WATCH: Replace after transactions are converted!
                transactionDb.saveTransactions(transactions);
            }
        }
    }

    public static class ATState {

        public final EsgKey dbKey;
        private final long atId;
        private byte[] state;
        private int prevHeight;
        private int nextHeight;
        private int sleepBetween;
        private long prevBalance;
        private boolean freezeWhenSameBalance;
        private long minActivationAmount;

        protected ATState(long atId, byte[] state,
                          int nextHeight, int sleepBetween, long prevBalance, boolean freezeWhenSameBalance, long minActivationAmount) {
            this.atId = atId;
            this.dbKey = atStateDbKeyFactory().newKey(this.atId);
            this.state = state;
            this.nextHeight = nextHeight;
            this.sleepBetween = sleepBetween;
            this.prevBalance = prevBalance;
            this.freezeWhenSameBalance = freezeWhenSameBalance;
            this.minActivationAmount = minActivationAmount;
        }


        public long getATId() {
            return atId;
        }

        public byte[] getState() {
            return state;
        }

        void setState(byte[] newState) {
            state = newState;
        }

        public int getPrevHeight() {
            return prevHeight;
        }

        void setPrevHeight(int prevHeight) {
            this.prevHeight = prevHeight;
        }

        public int getNextHeight() {
            return nextHeight;
        }

        void setNextHeight(int newNextHeight) {
            nextHeight = newNextHeight;
        }

        public int getSleepBetween() {
            return sleepBetween;
        }

        void setSleepBetween(int newSleepBetween) {
            this.sleepBetween = newSleepBetween;
        }

        public long getPrevBalance() {
            return prevBalance;
        }

        void setPrevBalance(long newPrevBalance) {
            this.prevBalance = newPrevBalance;
        }

        public boolean getFreezeWhenSameBalance() {
            return freezeWhenSameBalance;
        }

        void setFreezeWhenSameBalance(boolean newFreezeWhenSameBalance) {
            this.freezeWhenSameBalance = newFreezeWhenSameBalance;
        }

        public long getMinActivationAmount() {
            return minActivationAmount;
        }

        void setMinActivationAmount(long newMinActivationAmount) {
            this.minActivationAmount = newMinActivationAmount;
        }
    }
}
