package co.rsk.blockchain;

/**
 * Created by SerAdmin on 11/12/2018.
 */

import co.rsk.config.TestSystemProperties;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.db.ContractDetailsImpl;
import co.rsk.db.TrieStorePoolOnMemory;
import co.rsk.helpers.PerformanceTestHelper;
import co.rsk.trie.*;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.AccountState;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionExecutor;
import org.ethereum.crypto.HashUtil;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.datasource.KeyValueDataSource;
import org.ethereum.datasource.LevelDbDataSource;
import org.ethereum.db.*;
import org.junit.Ignore;
import org.junit.Test;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.spongycastle.util.encoders.Hex;

import java.io.File;
import java.io.FilenameFilter;
import java.math.BigInteger;
import java.util.*;

import static org.ethereum.db.IndexedBlockStore.BLOCK_INFO_SERIALIZER;
import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.ethereum.util.ByteUtil.toHexString;

public class RepositoryValidator  implements TrieIteratorListener {

    TrieImpl worldStateTrie;
    BlockStore blockStore;
    DetailsDataStore detailsDataStore;
    Map<String,AddressAttributes> addressAttributes;
    Map<ByteArrayWrapper,AddressAttributes> hashedAddresses;
    List<String> errors;
    List<String> warnings;
    long blockNumber;
    Set<RskAddress> historicalContractAddresses;
    private static final TestSystemProperties config = new TestSystemProperties();

    String databaseDir = "C:/Base/database-test-resmac";
    //String details_storage_dir = "C:/Base/details-storage";
    //String details_storage_dir = "C:\\Base\\details-storage";
    String details_storage_dir = databaseDir+"/details-storage";

    String state_dir = databaseDir+"/state";

    private class AddressAttributes {
        boolean hasDetailsDB;
        boolean canRetrieveDetails;
        boolean isAccount;
        boolean foundInAccountTrie;
        boolean isRemascSender;
        byte[] hashedAddress;
        byte[] address;
    }

    /**
     * Created by SerAdmin on 10/23/2018.
     */
    private static String getDataSourceName(RskAddress contractAddress) {
        return "details-storage/" + contractAddress;
    }

    public BlockStore buildBlockStore(String databaseDir) {
        File blockIndexDirectory = new File(databaseDir + "/blocks/");
        File dbFile = new File(blockIndexDirectory, "index");
        if (!blockIndexDirectory.exists()) {
            boolean mkdirsSuccess = blockIndexDirectory.mkdirs();
            if (!mkdirsSuccess) {
                System.out.println("Unable to create blocks directory: "+ blockIndexDirectory);
            }
        }

        DB indexDB = DBMaker.fileDB(dbFile)
                .closeOnJvmShutdown()
                .make();

        Map<Long, List<IndexedBlockStore.BlockInfo>> indexMap = indexDB.hashMapCreate("index")
                .keySerializer(Serializer.LONG)
                .valueSerializer(BLOCK_INFO_SERIALIZER)
                .counterEnable()
                .makeOrGet();

        KeyValueDataSource blocksDB = new LevelDbDataSource("blocks", databaseDir);
        blocksDB.init();

        return new IndexedBlockStore(indexMap, blocksDB, indexDB);
    }


    static boolean isHex(String c) {

        for ( int i = 1 ; i < c.length() ; i++ )
            if ( Character.digit(c.charAt(i), 16) == -1 )
                return false;
        return true;
    }

    public void init() {
        hashedAddresses = new HashMap<>();
        addressAttributes = new HashMap<>();
        errors= new ArrayList<String>();
        warnings = new ArrayList<String>();
    }


    void addPrecompiledContracts() {
        addRef("00","zeroAccount",false,false);
        //addRef("0000000000000000000000000000000001000000","Precomp");
        //addRef("0000000000000000000000000000000001000001","Precomp");
        //addRef("0000000000000000000000000000000001000002","Precomp");
        //addRef("0000000000000000000000000000000001000003","Precomp");
        //addRef("0000000000000000000000000000000001000004","Precomp");
        //addRef("0000000000000000000000000000000001000005","Precomp");
        addRef("0000000000000000000000000000000001000006","Precomp",true,false);
        //addRef("0000000000000000000000000000000001000007","Precomp");
        addRef("0000000000000000000000000000000001000008","Precomp",true,false);
    }

    void collectDetailsStoreKeys() {
        Set<RskAddress> historicalContractAddresses = detailsDataStore.keys();
        // Now I get all contractDetails, so that I can retrieve all addresses and match
        for (RskAddress a : historicalContractAddresses) {

            if (a == null) continue;
            byte[] hashedAddr = getAddressHash(a.getBytes());
            ByteArrayWrapper b = new ByteArrayWrapper(hashedAddr);
            AddressAttributes aa;
            // now don't pay attention to code hash, we just need to extract addresses
            if (!hashedAddresses.containsKey(b)) {
                aa = new AddressAttributes();
                aa.address = a.getBytes();
                aa.hashedAddress = hashedAddr;
                hashedAddresses.put(b, aa);
            } else {
                // Update: I don't think this is ever necessary
                aa = hashedAddresses.get(b);
                if (aa.address == null) {
                    aa.address = a.getBytes();
                }
            }
        }
    }

    public void collectDetailsDBs() {
        File file = new File(details_storage_dir);
        String[] directories = file.list(new FilenameFilter() {
            @Override
            public boolean accept(File current, String name) {
                return isHex(name) && (new File(current, name).isDirectory());
            }
        });
        for (String dir : directories) {
            // Extract RskAddress.
            //Path path = LevelDbDataSource.getPathForName(dir, databaseDir);

            // 40 hex chars
            if (dir.length()==40) {
                AddressAttributes aa = new AddressAttributes();
                addressAttributes.put(dir, aa);
                aa.address = Hex.decode(dir);
                aa.hasDetailsDB = true;
            } else if (dir.equals("00")) {
                // This is a special smart-contract for the address 0x00. I really
                // don't know why the platform makes an exception for this address.
                // I think is simply a mistake and sometime in the future everything is going
                // to blow up because of this little thing.
                    AddressAttributes aa = new AddressAttributes();
                addressAttributes.put(dir, aa);
                aa.address = new byte[1]; // filled with zeros
                aa.hasDetailsDB = true;

            }else
                System.out.println("Strange directory entry: "+dir);
        }
    }

    public byte[] getAddressHash(byte[] b) {
        //  Special case
        //if ((b.length==1) && (b[0]==0))
        return Keccak256Helper.keccak256(b);
    }

    public void computeHashedAddresses() {
        for (Map.Entry<String,AddressAttributes> e : addressAttributes.entrySet()) {
            e.getValue().hashedAddress =getAddressHash(e.getValue().address);
            hashedAddresses.put(new ByteArrayWrapper(e.getValue().hashedAddress),e.getValue());
        }
    }

    public void showState(String s ) {
        System.out.println();
        System.out.println(s);
    }

    public void collectInfo() {
        showState("Collecting static info...");
        collectDetailsDBs();
        computeHashedAddresses();
        config.getBlockchainConfig();
        collectDetailsStoreKeys();
    }


    void openDBs() {
        System.out.println("Opening DBs");
        blockStore = buildBlockStore(databaseDir);

        //
        LevelDbDataSource stateDB = new LevelDbDataSource("state",databaseDir);
        stateDB.init();
        TrieStore trieDataStore = new TrieStoreImpl(stateDB);

        worldStateTrie = new TrieImpl(trieDataStore ,true);

        LevelDbDataSource detailsDB =new LevelDbDataSource("details",databaseDir);
        detailsDB.init();
        DatabaseImpl db = new DatabaseImpl(detailsDB);
        //TrieStore.Pool  trieStorePool = new TrieStorePoolOnMemory();
        TrieStore.Pool  trieStorePool = new TrieStorePoolOnDisk(databaseDir);
        detailsDataStore = new DetailsDataStore(db, trieStorePool, 0);

    }

    void addRef(String addr,String name,boolean isPrecompiled,boolean isPure) {
        System.out.println("Adding "+addr+" "+name);
        RskAddress a = new RskAddress(addr);
        checkAddress(a,isPrecompiled,isPure);

    }

    @Ignore
    @Test
    public void validateRepository() {

        PerformanceTestHelper pth = new PerformanceTestHelper();
        pth.setup();
        pth.shortFormat = true;
        pth.startMeasure();
        init();
        openDBs();
        pth.endMeasure(); // partial result
        collectInfo();
        pth.endMeasure(); // partial result
        showState("Scanning the worldstate trie...");
        Block best =blockStore.getBestBlock();
        blockNumber = best.getNumber();
        byte[] worldStateRoot = best.getStateRoot();


        scanWorldTrieFor(worldStateRoot);
        //printAllAccounts();
        //Block block1 = blockStore.getBlockByHash(blockStore.getBlockHashByNumber(1));
        //System.out.println();
        //collectAddressesFromBlocks(1,1);
        //scanWorldTrieFor(block1.getStateRoot());
        pth.endMeasure(); // partial result
    }

    public void checkSomeSpecificContracts() {
        RskAddress asource = new RskAddress("0d310685491c48606e754ba4f76ea52584035c89");
        //checkAddress(asource );
        clearResults();

        RskAddress base = new RskAddress("31a74ab327a098dff37bce16c9437fe620bafb69");

        for(int i=0; i<16;i++) {
            RskAddress c = getContractAddress(base, i);
            byte[] hashedAddress = getAddressHash(c.getBytes());
            System.out.println("Testing address: " + c + " for nonce " + i);
            checkAddress(c,false,false);
        }
    }
    public void printAllAccounts() {
        for (Map.Entry<ByteArrayWrapper, AddressAttributes> e : hashedAddresses.entrySet()) {
            AddressAttributes aa = e.getValue();
            System.out.println("Address: "+toHexString(aa.address));
        }

    }

    public int  checkAddress(RskAddress base,boolean isPrecompiled,boolean isPure) {
        byte[] hashedAddress = getAddressHash(base.getBytes());
        AddressAttributes aa = hashedAddresses.get(new ByteArrayWrapper(hashedAddress));
        if (aa == null) {
            System.out.println("Inexistent address in details DBs: " + base);
            System.out.println("Manually adding "+base);
            aa = new AddressAttributes();
            hashedAddresses.put(new ByteArrayWrapper(hashedAddress),aa);
            aa.hashedAddress = hashedAddress;
        }
        byte[] accountValue;
        try {
            accountValue = worldStateTrie.get(base.getBytes());
        } catch (Exception e) {
            errors.add("Invalid account trie: " + e.getClass().getCanonicalName() + " " +
                    e.getMessage());
            //e.printStackTrace();
            return 102;
        }


        if ((accountValue==null) && (!isPure)) {
            System.out.println("Address " + base + "  not in trie, but should be.");
            return 100;
        }

        if ((accountValue!=null) && (isPure)) {
            System.out.println("Address " + base + "  is a pure precompiled contract and should not not be in trie");
            return 101;
        }
        if (accountValue==null) return 0;

        System.out.println("Address "+base+" present in wordstate trie");
        AccountState astate = new AccountState(accountValue);
        return processAccount(hashedAddress,base.getBytes(),astate);
    }

    public void clearResults() {
        errors.clear();
        warnings.clear();
    }

    public RskAddress getContractAddress(RskAddress base,int nonce) {
        return new RskAddress(HashUtil.calcNewAddr(base.getBytes(),
                BigInteger.valueOf(nonce).toByteArray()));
    }

    public void collectAddressesFromBlock(Block b) {
        List<Transaction> txList = b.getTransactionsList();
        for (Transaction t : txList) {
            RskAddress a = t.getReceiveAddress();
            if (a.getBytes().length == 0) continue;
            byte[] hashedAddress = getAddressHash(a.getBytes());
            AddressAttributes aa = hashedAddresses.get(new ByteArrayWrapper(hashedAddress));
            if (aa == null) {
                System.out.println("Found " + a + " is not on the trie. Why?");
            } else if (aa.address == null) {
                // found a match: fill it
                aa.address = a.getBytes();
                System.out.println("Found " + a + " as a normal account");
            }
        }
    }

   public void collectAddressesFromBlocks() {
       // Re=scan blocks
       byte[] bestHash = blockStore.getBestBlock().getHash().getBytes();
       while (bestHash!=null) {
           Block b = blockStore.getBlockByHash(bestHash);
           collectAddressesFromBlock(b);
           if (b.getNumber()==1)  {
               break;
           }
           bestHash = b.getParentHash().getBytes();
       }
   }
    public void collectAddressesFromBlocks(int last,int num) {
        byte[] bestHash = blockStore.getBlockHashByNumber(last);
        while (bestHash!=null) {
            Block b = blockStore.getBlockByHash(bestHash);
            collectAddressesFromBlock(b);
            if (b.getNumber()==1)  {
                break;
            }
            num--;
            if (num==0) return;
            bestHash = b.getParentHash().getBytes();
        }
    }

   public void scanWorldTrieFor(byte[] stateRoot) {

       worldStateTrie = (TrieImpl) worldStateTrie.getSnapshotTo(new Keccak256(stateRoot));
       // addPrecompiledContracts will look into the current account trie
       // that's why it needs to be executed after modifying the trie root.
       addPrecompiledContracts();
       TrieAccountScanner tas = new TrieAccountScanner();
       try {
           int ret = tas.scanTrie(new ExpandedKeyImpl(), worldStateTrie, this, 8 * 32);
           if (ret != 0)
               errors.add("Account trie processing error code: " + ret);
       } catch (Exception e) {
           errors.add("Invalid account trie: " + e.getClass().getCanonicalName() + " " +
                   e.getMessage());
           //e.printStackTrace();
       }

       for (Map.Entry<ByteArrayWrapper, AddressAttributes> e : hashedAddresses.entrySet()) {
           AddressAttributes aa = e.getValue();
           if ((!aa.isAccount) && (!aa.foundInAccountTrie) && (aa.hasDetailsDB)) {
               warnings.add("Contract " + toHexString(e.getValue().address) + " does not exists anymore. It may have self-destructed or it may belong to a fork.");
           }
       }
       printResults();
   }

    void printResults() {
        System.out.println("Warnings:");
        warnings.forEach(System.out::println);
        System.out.println();
        System.out.println("Errors:");
        errors.forEach(System.out::println);
    }


    public int process(byte[] hashedKey, byte[] value) {
        // The hashed address is returned in binary format.
        // Must reencode.
        byte[] hashedAddress = PathEncoder.encode(hashedKey);

        AccountState astate = new AccountState(value);
        return processAccount(hashedAddress, null, astate);
    }
    byte[] zeroAddr = Hex.decode("bc36789e7a1e281436464229828f817d6612f7b477d66591ff96a9e064bcc98a");

    public int processAccount(byte[] hashedAddress, byte[] optionalAddress,AccountState astate) {
        // Now we can get the data from the details db
        // Find pre-image hashedAddress
        AddressAttributes aa = hashedAddresses.get(new ByteArrayWrapper(hashedAddress));
        boolean internalContract;

        boolean emptyCodeHash =astate.getCodeHash().equals(AccountState.EMPTY_DATA_HASH);
        boolean emptyStorage = astate.getStateRoot().equals(HashUtil.EMPTY_TRIE_HASH);


        if (Arrays.equals(hashedAddress,zeroAddr)) {
            // this is the zero address
            //System.out.println("found zero");
        }
        if (aa==null) {
            // it seems that there is no independant Details storage
            // there still may be an internal contract details, but we can't find it because
            // it's located by contract address, and we don't have it.


            if (emptyCodeHash) {
                // it seems that it's an Account. It's still can be a contract without code
                if (emptyStorage) {
                    // Yes it's an account, there could still be a record in contract details db
                    // but can it bother us ? I think not. We should check what getCode does.
                    aa.isAccount = true;
                    return 0;
                }
            }

            // This is a state where there is a contract that has no external DB. We really can't
            // continue performing verifications.
            // log the error, but keep scanning
            errors.add("Contract hashedaddr "+toHexString(hashedAddress)+" is not present anywhere else.");
            internalContract =true;
            aa = new AddressAttributes();
            hashedAddresses.put(new ByteArrayWrapper(hashedAddress),aa);
            aa.hashedAddress = hashedAddress;
            // Cannot fill aa.address because we don't know
            if (optionalAddress==null)
                return 0;

        }
        if (optionalAddress!=null)
            aa.address = optionalAddress;

        aa.foundInAccountTrie = true;

        if ((aa.address.length==1) && (aa.address[0]==0)) {
            // this is also the zero address
            // is is a special case because you can't create an RskAddress of a single
            // zero byte. Well, I modified RskAddress to allow it. And it should be.
            // System.out.println("found zero1");
            aa.isRemascSender = true;
        }

        RskAddress addr = new RskAddress(aa.address);
        ContractDetails details;
        try {
            details = detailsDataStore.get(addr, astate.getCodeHash());

            if (details==null) {
                System.out.println("ADDRESS:" + addr);
                errors.add("Contract " + addr + ": cannot retrieve details");
                return 0;
            }
        } catch (Exception e) {
            System.out.println("ADDRESS:" + addr);
            errors.add("Contract " + addr + " throws when tries to retrieve details");
            return 0;
        }
        aa.canRetrieveDetails = true;

        if (aa.isRemascSender) {
            if (!Arrays.equals(astate.getStateRoot(),HashUtil.EMPTY_TRIE_HASH)) {
                errors.add("RemascSender should have no storage");
            }
            if (!Arrays.equals(astate.getCodeHash(),AccountState.EMPTY_DATA_HASH)) {
                errors.add("RemascSender should have no code");
            }
            if (!astate.getBalance().asBigInteger().equals(BigInteger.ZERO)) {
                errors.add("RemascSender should have zero balance");
            }
            if (!astate.getNonce().equals(BigInteger.valueOf(blockNumber))) {
                errors.add("RemascSender should match the block number");
            }

        }
        try {

            byte[] code = details.getCode();
            if (code==null)
                errors.add("Contract "+addr+": has null code");
                else {
                    byte[] codeHash = Keccak256Helper.keccak256(code);
                    if (!Arrays.equals(astate.getCodeHash(),codeHash)) {
                        errors.add("Contract "+addr+": has wrong code. Expected hash: "+
                        toHexString(astate.getCodeHash())+" found hash: "+
                        toHexString(codeHash));
                    }
            }


        } catch (Exception e) {
            errors.add("Contract "+addr+": cannot retrieve code");
        }
        // Now scan the whole trie and make sure all nodes exists
        TrieImpl contractStorageTrie = (TrieImpl) ((ContractDetailsImpl) details).getTrie();

        if (!Arrays.equals(astate.getStateRoot(),details.getStorageHash())) {
            warnings.add("Contract "+addr+": has outdated/future storage root. Expected hash: "+
                    toHexString(astate.getStateRoot())+" found hash: "+
                    toHexString(details.getStorageHash())+". This can happen if you're exploring a past state.");

            contractStorageTrie = (TrieImpl) contractStorageTrie.getSnapshotTo(new Keccak256(astate.getStateRoot()));
        }


        TrieAccountScanner tas = new TrieAccountScanner();
        // Do not call any processor
        try {
            //TrieImpl.enableErrors = true;
            int ret = tas.scanTrie(new ExpandedKeyImpl(), contractStorageTrie, null, 8 * 32);
            //TrieImpl.enableErrors = false;
            if (ret!=0)
                errors.add("Contract "+addr+":processing error code: "+ret);
        } catch (Exception e) {
          errors.add("Contract "+addr+": invalid storage: "+e.getClass().getCanonicalName()+" "+
                  e.getMessage());
        }
        if (!Arrays.equals(aa.address,details.getAddress())) {
            errors.add("Contract "+addr+": has wrong address in details. found: "+
                    toHexString(details.getAddress()));
        }
        return 0;
    }


}