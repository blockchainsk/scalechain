package io.scalechain.blockchain.chain

import java.io.File

import io.scalechain.blockchain.chain.processor.BlockProcessor
import io.scalechain.blockchain.proto.codec.{TransactionCodec, BlockHeaderCodec}
import io.scalechain.blockchain.{ChainException, ErrorCode, GeneralException}
import io.scalechain.blockchain.chain.mining.BlockTemplate
import io.scalechain.blockchain.proto._
import io.scalechain.blockchain.script.HashSupported._
import io.scalechain.blockchain.storage.{BlockInfoFactory, BlockStorage, Storage, DiskBlockStorage, GenesisBlock}
import io.scalechain.blockchain.storage

import io.scalechain.blockchain.transaction._
import io.scalechain.util.Utils
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.TailCalls.TailRec


object Blockchain {
  var theBlockchain : Blockchain = null
  def create(storage : BlockStorage) = {
    theBlockchain = new Blockchain(storage)

    // Load any in memory structur required by the Blockchain class from the on-disk storage.
    new BlockchainLoader(theBlockchain, storage).load()
    theBlockchain
  }
  def get() = {
    assert( theBlockchain != null)
    theBlockchain
  }
}


class BlockchainLoader(chain:Blockchain, storage : BlockStorage) {

  def load() : Unit = {
    val bestBlockHashOption = storage.getBestBlockHash()
    if (bestBlockHashOption.isDefined) {
      // Set the best block descriptor.
      chain.theBestBlock  = storage.getBlockInfo(bestBlockHashOption.get).get
    } else {
      // We don't have the best block hash yet.
      // This means that we did not put the genesis block yet.
      // On the CLI layer, while initializing all layers, the genesis block will be put, so we do nothing here.
    }
  }
}

/** Maintains the best blockchain, whose chain work is the biggest one.
  *
  * The block metadata is kept in a tree data structure on-disk.
  * The actual block data is also kept on-disk.
  *
  * [ Overview ]
  *
  * The chain work for a block is the total number of hash calculations from block 0 to the current best block.
  *
  * For example, if we calculated hashes 10, 20, 15 times for three blocks B0, B1, and B2, the chain work is 45(10+20+15).
  *
  *   B0(10) → B1(10+20) → B2(10+20+15) : The best chain.
  *
  * Based on the total chain work of the new block, we decide the best blockchain.
  * For example, if we found a block B2' whose chain work(50) is greater than the current maxium(45),
  * we will keep B2' as the best block and update the best blockchain.
  *
  *   B0(10) → B1(10+20) → B2'(10+20+20) : The best chain.
  *                      ↘ B2(10+20+15) : This is a fork.
  *
  * When a new block B3 is added to the blockchain, we will add it on top of the best blockchain.
  *
  *   B0 → B1 → B2' → B3 : The best chain.
  *           ↘ B2
  *
  * Only transactions in the best blockchain remain effective.
  * Because B2 remains in a fork, all transactions in B2 are migrated to the disk-pool, except ones that are included in B3.
  *
  * The disk-pool is where transactions that are not in any block of the best blockchain are stored.
  * ( Bitcoin core stores transactions in memory using mempool, but ScaleChain stores transactions on-disk using disk-pool ;-). )
  * TransactionDescriptor can either store record location of the transaction if the transaction was written as part of a block on disk.
  * Otherwise, TransactionDescriptor can stores a serialized transaction, and TransactionDescriptor itself is stored as a value of RocksDB keyed by the transaction hash.
  *
  * Of course, block a reorganization can invalidate more than two blocks at once.
  *
  * Time T :
  *   B0(10) → B1(30) → B2(45) : The best chain.
  *
  * Time T+1 : Add B1' (chain work = 35)
  *   B0(10) → B1(30) → B2(45) : The best chain.
  *          ↘ B1'(35)
  *
  * Time T+2 : Add B2' (chain work = 55)
  *   B0(10) → B1(30) → B2(45)
  *          ↘ B1'(35) -> B2'(55) : The best chain.
  *
  * In this case all transactions in B1, B2 but not in B1' and B2' are moved to the disk-pool so that they can be added to
  * the block chain later when a new block is created.
  *
  */
class Blockchain(storage : BlockStorage) extends BlockchainView  {
  private val logger = LoggerFactory.getLogger(classOf[Blockchain])

  var chainEventListener : Option[ChainEventListener] = None

  val txMagnet = new TransactionMagnet(storage)
  val txPool = new TransactionPool(storage, txMagnet)
  val blockMagnet = new BlockMagnet(storage, txPool, txMagnet)

  val blockOrphanage = new BlockOrphanage(storage)
  val txOrphanage = new TransactionOrphanage(storage)

  /** Set an event listener of the blockchain.
    *
    * @param listener The listener that wants to be notified for new blocks, invalidated blocks, and transactions comes into and goes out from the transaction pool.
    */
  def setEventListener( listener : ChainEventListener ): Unit = {
    chainEventListener = Some(listener)
    blockMagnet.setEventListener(listener)
  }

  /** The descriptor of the best block.
    * This value is updated whenever a new best block is found.
    * We also have to check if we need to do block reorganization whenever this field is updated.
    */
  var theBestBlock : BlockInfo = null

  /**
    * Put the best block hash into on-disk storage, as well as the in-memory best block info.
    *
    * @param blockHash
    * @param blockInfo
    */
  protected[chain] def setBestBlock(blockHash : Hash, blockInfo : BlockInfo) : Unit = {
    theBestBlock = blockInfo
    storage.putBestBlockHash(blockHash)
  }

  /** Put a block onto the blockchain.
    *
    * (1) During initialization, we call putBlock for each block we received until now.
    * (2) During IBD(Initial Block Download), we call putBlock for blocks we downloaded.
    * (3) When a new block was received from a peer.
    *
    * Caller of this method should check if the bestBlock was changed.
    * If changed, we need to update the best block on the storage layer.
    *
    * TODO : Need to check the merkle root hash in the block.
    *
    * @param block The block to put into the blockchain.
    * @return true if the newly accepted block became the new best block.
    *
    */
  def putBlock(blockHash : Hash, block:Block) : Boolean = {
    // TODO : BUGBUG : Need to think about RocksDB transactions.

    synchronized {
      if (storage.hasBlock(blockHash)) {
        logger.info(s"Duplicate block was ignored. Block hash : ${blockHash}")
        false
      } else {

        // Case 1. If it is the genesis block, set the genesis block as the current best block.
        if (block.header.hashPrevBlock.isAllZero()) {
          assert(theBestBlock == null)
          storage.putBlock(block)
          storage.putBlockHashByHeight(0, blockHash)

          setBestBlock(blockHash, storage.getBlockInfo(blockHash).get )

          chainEventListener.map(_.onAttachBlock(ChainBlock( height = 0, block)))

          true
        } else { // Case 2. Not a genesis block.
          assert(theBestBlock != null)

          // Step 2.1 : Get the block descriptor of the previous block.
          val prevBlockDesc: Option[BlockInfo] = storage.getBlockInfo(block.header.hashPrevBlock)
          // We already checked if the parent block exists.
          assert(prevBlockDesc.isDefined)

          val prevBlockHash = prevBlockDesc.get.blockHeader.hash

          storage.putBlock(block)
          storage.updateNextBlockHash(prevBlockHash, Some(blockHash))
          val blockInfo = storage.getBlockInfo(blockHash).get

          // Case 2.A : The previous block of the new block is the current best block.
          if (prevBlockHash.value == theBestBlock.blockHeader.hash.value) {
            // Step 2.A.1 : Update the best block
            storage.putBlockHashByHeight(blockInfo.height, blockHash)

            setBestBlock( blockHash, blockInfo )

            // TODO : Update best block in wallet (so we can detect restored wallets)

            // Step 2.A.2 : Remove transactions in the block from the disk-pool.
            block.transactions.foreach { transaction =>
              storage.delTransactionFromPool(transaction.hash)
            }

            chainEventListener.map(_.onAttachBlock(ChainBlock(height = blockInfo.height, block)))

            logger.info(s"Successfully have put the block in the best blockchain.\n Height : ${blockInfo.height}, Hash : ${blockHash}")
            true
          } else { // Case 2.B : The previous block of the new block is NOT the current best block.
            // Step 3.B.1 : See if the chain work of the new block is greater than the best one.
            if (blockInfo.chainWork > theBestBlock.chainWork) {
              logger.warn(s"Block reorganization started. Original Best : (${theBestBlock.blockHeader.hash},${theBestBlock}), The new Best (${blockInfo.blockHeader.hash},${blockInfo})")

              // Step 3.B.2 : Reorganize the blocks.
              // transaction handling, orphan block handling is done in this method.
              blockMagnet.reorganize(originalBestBlock = theBestBlock, newBestBlock = blockInfo)

              // Step 3.B.3 : Update the best block
              setBestBlock(blockHash, blockInfo)

              // TODO : Update best block in wallet (so we can detect restored wallets)
              true
            } else {
              logger.warn(s"A block was added to a fork. The current Best : (${theBestBlock.blockHeader.hash},${theBestBlock}), The best on the fork : (${blockInfo.blockHeader.hash},${blockInfo})")
              false
            }
          }
        }
      }
    }
  }
/*
  /**
    * Put a block header. The logic is almost identical to the putBlock method except the block reorganization part.
    *
    * Note : the next block hash is not updated.
    *
    * @param blockHash The hash of the block header.
    * @param blockHeader The block header.
    */
  def putBlockHeader(blockHash : Hash, blockHeader:BlockHeader) : Unit = {
    // TODO : Implement
    logger.warn("Headers-first IBD is not supported yet.")
    assert(false)
  }
*/
  /** Put a transaction we received from peers into the disk-pool.
    *
    * @param transaction The transaction to put into the disk-pool.
    */
  def putTransaction(txHash : Hash, transaction : Transaction) : Unit = {
    synchronized {
      // TODO : BUGBUG : Need to start a RocksDB transaction.
      try {
        // Step 1 : Add transaction to the transaction pool.
        txPool.addTransactionToPool(txHash, transaction)
        // TODO : BUGBUG : Need to commit the RocksDB transaction.
        // Step 2 : Notify event listeners that a new transaction was added.
        chainEventListener.map(_.onNewTransaction(transaction))

      } finally {
        // TODO : BUGBUG : Need to rollback the RocksDB transaction if any exception raised.
        // Only some of inputs might be connected. We need to revert the connection if any error happens.
      }
    }
  }

  /** Return an iterator that iterates each ChainBlock from a given height.
    *
    * Used by : importAddress RPC to rescan the blockchain.
    *
    * @param height Specifies where we start the iteration. The height 0 means the genesis block.
    * @return The iterator that iterates each ChainBlock.
    */
  def getIterator(height : Long) : Iterator[ChainBlock] = {
    // TODO : Implement
    assert(false)
    null
  }

  /** Return the block height of the best block.
    *
    * @return The best block height.
    */
  def getBestBlockHeight() : Long = {
    assert(theBestBlock != null)
    theBestBlock.height
  }

  /** Return the hash of block on the tip of the best blockchain.
    *
    * @return The best block hash.
    */
  def getBestBlockHash() : Option[Hash] = {
    storage.getBestBlockHash()
  }


  /** Get the hash of a block specified by the block height on the best blockchain.
    *
    * Used by : getblockhash RPC.
    *
    * @param blockHeight The height of the block.
    * @return The hash of the block header.
    */
  def getBlockHash(blockHeight : Long) : Hash = {
    val blockHashOption = storage.getBlockHashByHeight(blockHeight)
    // TODO : Bitcoin Compatiblity : Make the error code compatible when the block height was a wrong value.
    if (blockHashOption.isEmpty) {

      throw new ChainException( ErrorCode.InvalidBlockHeight)
    }
    blockHashOption.get
  }

  /**
    * Used by BlockLocator to get the info of the given block.
    *
    * @param blockHash The hash of the block to get the info of it.
    * @return Some(blockInfo) if the block exists; None otherwise.
    */
  def getBlockInfo(blockHash : Hash) : Option[BlockInfo] = {
    storage.getBlockInfo(blockHash)
  }



  /** See if a block exists on the blockchain.
    *
    * Used by : submitblock RPC to check if a block already exists.
    *
    * @param blockHash The hash of the block header to check.
    * @return true if the block exists; false otherwise.
    */
  def hasBlock(blockHash : Hash) : Boolean = {
    storage.hasBlock(blockHash)
  }

  /** Get a block searching by the header hash.
    *
    * Used by : getblock RPC.
    *
    * @param blockHash The header hash of the block to search.
    * @return The searched block.
    */
  def getBlock(blockHash : Hash) : Option[(BlockInfo, Block)] = {
    storage.getBlock(blockHash)
  }


  /** Get a block header by the header hash.
    *
    * @param blockHash The hash of the block header.
    * @return The block header.
    */
  def getBlockHeader(blockHash : Hash) : Option[BlockHeader] = {
    storage.getBlockHeader(blockHash)
  }

  /** Return a transaction that matches the given transaction hash.
    *
    * Used by listtransaction RPC to get the
    *
    * @param txHash The transaction hash to search.
    * @return Some(transaction) if the transaction that matches the hash was found. None otherwise.
    */
  def getTransaction(txHash : Hash) : Option[Transaction] = {
    // Note : No need to search transaction pool, as storage.getTransaction searches the transaction pool as well.

    // Step 1 : Search block database.
    val dbTransactionOption = storage.getTransaction( txHash )

    // Step 3 : TODO : Run validation.

    //BUGBUG : Transaction validation fails because the transaction hash on the outpoint does not exist.
    //poolTransactionOption.foreach( new TransactionVerifier(_).verify(DiskBlockStorage.get) )
    //dbTransactionOption.foreach( new TransactionVerifier(_).verify(DiskBlockStorage.get) )

    dbTransactionOption
  }

  /** Check if the transaction exists either in a block on the best blockchain or on the transaction pool.
    *
    * @param txHash The hash of the transaction to check the existence.
    * @return true if we have the transaction; false otherwise.
    */
  def hasTransaction(txHash : Hash) : Boolean = {
    storage.getTransactionDescriptor(txHash).isDefined
  }

  /** Return a transaction output specified by a give out point.
    *
    * @param outPoint The outpoint that points to the transaction output.
    * @return The transaction output we found.
    */
  def getTransactionOutput(outPoint : OutPoint) : TransactionOutput = {
    // Coinbase outpoints should never come here
    assert( !outPoint.transactionHash.isAllZero() )

    val transaction = getTransaction(outPoint.transactionHash)
    if (transaction.isEmpty) {
      throw new ChainException(ErrorCode.InvalidOutPoint, "The transaction was not found : " + outPoint.transactionHash)
    }

    val outputs = transaction.get.outputs

    if( outPoint.outputIndex >= outputs.length) {
      throw new ChainException(ErrorCode.InvalidOutPoint, s"Invalid output index. Transaction hash : ${outPoint.transactionHash}, Output count : ${outputs.length}, Output index : ${outPoint.outputIndex}")
    }

    outputs(outPoint.outputIndex)
  }
}

