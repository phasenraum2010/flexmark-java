package com.vladsch.flexmark.internal;

import com.vladsch.flexmark.internal.util.*;
import com.vladsch.flexmark.internal.util.collection.*;
import com.vladsch.flexmark.internal.util.dependency.DependencyResolver;
import com.vladsch.flexmark.internal.util.dependency.ResolvedDependencies;
import com.vladsch.flexmark.node.*;
import com.vladsch.flexmark.parser.DelimiterProcessor;
import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.InlineParserFactory;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.block.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.stream.Collectors;

public class DocumentParser implements ParserState {

    public static final InlineParserFactory INLINE_PARSER_FACTORY = new InlineParserFactory() {
        @Override
        public InlineParser inlineParser(DataHolder options, BitSet specialCharacters, BitSet delimiterCharacters, Map<Character, DelimiterProcessor> delimiterProcessors, LinkRefProcessorData linkRefProcessors) {
            return new CommonmarkInlineParser(options, specialCharacters, delimiterCharacters, delimiterProcessors, linkRefProcessors);
        }
    };

    private static HashMap<CustomBlockParserFactory, DataKey<Boolean>> CORE_FACTORIES_DATA_KEYS = new HashMap<>();
    static {
        CORE_FACTORIES_DATA_KEYS.put(new BlockQuoteParser.Factory(), Parser.BLOCK_QUOTE_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new HeadingParser.Factory(), Parser.HEADING_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new FencedCodeBlockParser.Factory(), Parser.FENCED_CODE_BLOCK_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new HtmlBlockParser.Factory(), Parser.HTML_BLOCK_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new ThematicBreakParser.Factory(), Parser.THEMATIC_BREAK_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new ListBlockParser.Factory(), Parser.LIST_BLOCK_PARSER);
        CORE_FACTORIES_DATA_KEYS.put(new IndentedCodeBlockParser.Factory(), Parser.INDENTED_CODE_BLOCK_PARSER);
    }

    private static List<CustomBlockParserFactory> CORE_FACTORIES = new ArrayList<>();
    static {
        CORE_FACTORIES.add(new BlockQuoteParser.Factory());
        CORE_FACTORIES.add(new HeadingParser.Factory());
        CORE_FACTORIES.add(new FencedCodeBlockParser.Factory());
        CORE_FACTORIES.add(new HtmlBlockParser.Factory());
        CORE_FACTORIES.add(new ThematicBreakParser.Factory());
        CORE_FACTORIES.add(new ListBlockParser.Factory());
        CORE_FACTORIES.add(new IndentedCodeBlockParser.Factory());
    }

    private static HashMap<DataKey<Boolean>, ParagraphPreProcessorFactory> CORE_PARAGRAPH_PRE_PROCESSORS = new HashMap<>();
    static {
        CORE_PARAGRAPH_PRE_PROCESSORS.put(Parser.REFERENCE_PARAGRAPH_PRE_PROCESSOR, new ReferencePreProcessorFactory());
    }

    private static HashMap<DataKey<Boolean>, BlockPreProcessorFactory> CORE_BLOCK_PRE_PROCESSORS = new HashMap<>();
    static {
        //CORE_BLOCK_PRE_PROCESSORS.put(Parser.REFERENCE_PARAGRAPH_PRE_PROCESSOR, new ReferencePreProcessorFactory());
    }

    private BasedSequence line;
    private BasedSequence lineWithEOL;

    /**
     * current line number in the input
     */
    private int lineNumber = 0;

    /**
     * current start of line offset in the input
     */
    private int lineStart = 0;

    /**
     * current lines EOL sequence
     */
    private int lineEOLIndex = 0;

    /**
     * current end of line offset in the input including EOL
     */
    private int lineEndIndex = 0;

    /**
     * current index (offset) in input line (0-based)
     */
    private int index = 0;

    /**
     * current column of input line (tab causes column to go to next 4-space tab stop) (0-based)
     */
    private int column = 0;

    /**
     * if the current column is within a tab character (partially consumed tab)
     */
    private boolean columnIsInTab;

    private int nextNonSpace = 0;
    private int nextNonSpaceColumn = 0;
    private int indent = 0;
    private boolean blank;

    private final List<BlockParserFactory> blockParserFactories;
    private final ParagraphPreProcessorDependencies paragraphPreProcessorDependencies;
    private final BlockPreProcessorDependencies blockPreProcessorDependencies;
    private final InlineParser inlineParser;
    private final DocumentBlockParser documentBlockParser;

    private List<BlockParser> activeBlockParsers = new ArrayList<>();


    private static class BlockParserMapper implements Computable<Block, BlockParser> {
        final public static BlockParserMapper INSTANCE = new BlockParserMapper();

        private BlockParserMapper() {
        }

        @Override
        public Block compute(BlockParser value) {
            return value.getBlock();
        }
    }

    private static class ObjectClassifier implements Computable<Class<?>, Object> {
        final public static ObjectClassifier INSTANCE = new ObjectClassifier();

        private ObjectClassifier() {
        }

        @Override
        public Class<?> compute(Object value) {
            return value.getClass();
        }
    }

    private static class NodeClassifier implements Computable<Class<? extends Node>, Node> {
        final public static NodeClassifier INSTANCE = new NodeClassifier();

        private NodeClassifier() {
        }

        @Override
        public Class<? extends Node> compute(Node value) {
            return value.getClass();
        }
    }

    private static class BlockClassifier implements Computable<Class<? extends Block>, Block> {
        final public static BlockClassifier INSTANCE = new BlockClassifier();

        private BlockClassifier() {
        }

        @Override
        public Class<? extends Block> compute(Block value) {
            return value.getClass();
        }
    }

    private class BlockParserOrderedSetHost implements OrderedSetHost<BlockParser> {

        public BlockParserOrderedSetHost() {
        }

        @Override
        public void adding(int index, BlockParser parser, Object v) {
            Block block = parser.getBlock();
            if (block != null) classifiedBlockBag.add(block);
        }

        @Override
        public Object removing(int index, BlockParser parser) {
            Block block = parser.getBlock();
            if (block != null) classifiedBlockBag.remove(block);
            return null;
        }

        @Override
        public void clearing() {
             classifiedBlockBag.clear();
        }

        @Override
        public void addingNull(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostInUpdate() {
            return false;
        }
    }

    private OrderedSet<BlockParser> allBlockParsers = new OrderedSet<>();
    
    private ClassifiedBag<Class<? extends Block>, Block> classifiedBlockBag = new ClassifiedBag<Class<? extends Block>, Block>(BlockClassifier.INSTANCE);
    
    private Map<Block, BlockParser> allBlocksParserMap = new HashMap<>();

    private Map<Class<? extends Block>, List<Block>> allPreProcessBlocks = new HashMap<>();
    private ArrayList<Paragraph> allParagraphsList = new ArrayList<>();

    private Map<Node, Boolean> lastLineBlank = new HashMap<>();
    final private DataHolder options;
    private ParserPhase currentPhase = ParserPhase.NONE;

    @Override
    public ParserPhase getParserPhase() {
        return currentPhase;
    }

    public static class ParagraphPreProcessorDependencies extends ResolvedDependencies<ParagraphPreProcessorDependencyStage> {
        public ParagraphPreProcessorDependencies(List<ParagraphPreProcessorDependencyStage> dependentStages) {
            super(dependentStages);
        }
    }

    public static class ParagraphPreProcessorDependencyStage {
        final private List<ParagraphPreProcessorFactory> dependents;

        public ParagraphPreProcessorDependencyStage(List<ParagraphPreProcessorFactory> dependents) {
            // compute mappings
            this.dependents = dependents;
        }
    }

    private static class ParagraphDependencyResolver extends DependencyResolver<ParagraphPreProcessorFactory, ParagraphPreProcessorDependencyStage, ParagraphPreProcessorDependencies> {
        @Override
        protected Class<? extends ParagraphPreProcessorFactory> getDependentClass(ParagraphPreProcessorFactory dependent) {
            return dependent.getClass();
        }

        @Override
        protected ParagraphPreProcessorDependencies createResolvedDependencies(List<ParagraphPreProcessorDependencyStage> stages) {
            return new ParagraphPreProcessorDependencies(stages);
        }

        @Override
        protected ParagraphPreProcessorDependencyStage createStage(List<ParagraphPreProcessorFactory> dependents) {
            return new ParagraphPreProcessorDependencyStage(dependents);
        }
    }

    public static class BlockPreProcessorDependencyStage {
        final private Map<Class<? extends Block>, List<BlockPreProcessorFactory>> factoryMap;
        final private List<BlockPreProcessorFactory> dependents;

        public BlockPreProcessorDependencyStage(List<BlockPreProcessorFactory> dependents) {
            // compute mappings
            HashMap<Class<? extends Block>, List<BlockPreProcessorFactory>> map = new HashMap<>();

            for (BlockPreProcessorFactory dependent : dependents) {
                Set<Class<? extends Block>> blockTypes = dependent.getBlockTypes();
                for (Class<? extends Block> blockType : blockTypes) {
                    List<BlockPreProcessorFactory> factories = map.get(blockType);
                    if (factories == null) {
                        factories = new ArrayList<>();
                        map.put(blockType, factories);
                    }
                    factories.add(dependent);
                }
            }

            this.dependents = dependents;
            this.factoryMap = map;
        }
    }

    public static class BlockPreProcessorDependencies extends ResolvedDependencies<BlockPreProcessorDependencyStage> {
        final private Set<Class<? extends Block>> blockTypes;
        final private Set<BlockPreProcessorFactory> blockPreProcessorFactories;

        public BlockPreProcessorDependencies(List<BlockPreProcessorDependencyStage> dependentStages) {
            super(dependentStages);
            Set<Class<? extends Block>> blockTypes = new HashSet<>();
            Set<BlockPreProcessorFactory> blockPreProcessorFactories = new HashSet<>();
            for (BlockPreProcessorDependencyStage stage : dependentStages) {
                blockTypes.addAll(stage.factoryMap.keySet());
                blockPreProcessorFactories.addAll(stage.dependents);
            }
            this.blockPreProcessorFactories = blockPreProcessorFactories;
            this.blockTypes = blockTypes;
        }

        public Set<Class<? extends Block>> getBlockTypes() {
            return blockTypes;
        }

        public Set<BlockPreProcessorFactory> getBlockPreProcessorFactories() {
            return blockPreProcessorFactories;
        }
    }

    private static class BlockDependencyResolver extends DependencyResolver<BlockPreProcessorFactory, BlockPreProcessorDependencyStage, BlockPreProcessorDependencies> {
        @Override
        protected Class<? extends BlockPreProcessorFactory> getDependentClass(BlockPreProcessorFactory dependent) {
            return dependent.getClass();
        }

        @Override
        protected BlockPreProcessorDependencies createResolvedDependencies(List<BlockPreProcessorDependencyStage> stages) {
            return new BlockPreProcessorDependencies(stages);
        }

        @Override
        protected BlockPreProcessorDependencyStage createStage(List<BlockPreProcessorFactory> dependents) {
            return new BlockPreProcessorDependencyStage(dependents);
        }
    }

    public DocumentParser(DataHolder options, List<CustomBlockParserFactory> customBlockParserFactories, ParagraphPreProcessorDependencies paragraphPreProcessorDependencies, BlockPreProcessorDependencies blockPreProcessorDependencies, InlineParser inlineParser) {
        this.options = options;
        
        ArrayList<BlockParserFactory> blockParserFactories = new ArrayList<>(customBlockParserFactories.size());
        for (CustomBlockParserFactory factory : customBlockParserFactories) {
            blockParserFactories.add(factory.create(options));
        }
        
        this.blockParserFactories = blockParserFactories;
        this.paragraphPreProcessorDependencies = paragraphPreProcessorDependencies;
        this.blockPreProcessorDependencies = blockPreProcessorDependencies;
        this.inlineParser = inlineParser;

        this.documentBlockParser = new DocumentBlockParser();
        activateBlockParser(this.documentBlockParser);
        this.currentPhase = ParserPhase.STARTING;
    }

    @Override
    public MutableDataHolder getProperties() {
        return documentBlockParser.getBlock();
    }

    public static List<CustomBlockParserFactory> calculateBlockParserFactories(DataHolder options, List<CustomBlockParserFactory> customBlockParserFactories) {
        List<CustomBlockParserFactory> list = new ArrayList<>();
        // By having the custom factories come first, extensions are able to change behavior of core syntax.
        list.addAll(customBlockParserFactories);

        // need to keep core parsers in the right order
        for (CustomBlockParserFactory factory : CORE_FACTORIES) {
            DataKey<Boolean> key = CORE_FACTORIES_DATA_KEYS.get(factory);

            if (key == null || options.get(key)) {
                list.add(factory);
            }
        }
        return list;
    }

    public static ParagraphPreProcessorDependencies calculateParagraphPreProcessors(DataHolder options, List<ParagraphPreProcessorFactory> blockPreProcessors, InlineParserFactory inlineParserFactory) {
        List<ParagraphPreProcessorFactory> list = new ArrayList<>();
        // By having the custom factories come first, extensions are able to change behavior of core syntax.
        list.addAll(blockPreProcessors);

        if (inlineParserFactory == INLINE_PARSER_FACTORY) {
            list.addAll(CORE_PARAGRAPH_PRE_PROCESSORS.keySet().stream().filter(options::get).map(key -> CORE_PARAGRAPH_PRE_PROCESSORS.get(key)).collect(Collectors.toList()));
        }

        ParagraphDependencyResolver resolver = new ParagraphDependencyResolver();
        return resolver.resolveDependencies(list);
    }

    public static BlockPreProcessorDependencies calculateBlockPreProcessors(DataHolder options, List<BlockPreProcessorFactory> blockPreProcessors, InlineParserFactory inlineParserFactory) {
        List<BlockPreProcessorFactory> list = new ArrayList<>();
        // By having the custom factories come first, extensions are able to change behavior of core syntax.
        list.addAll(blockPreProcessors);

        // add core block preprocessors
        list.addAll(CORE_BLOCK_PRE_PROCESSORS.keySet().stream().filter(options::get).map(key -> CORE_BLOCK_PRE_PROCESSORS.get(key)).collect(Collectors.toList()));

        BlockDependencyResolver resolver = new BlockDependencyResolver();
        return resolver.resolveDependencies(list);
    }

    @Override
    public InlineParser getInlineParser() {
        return inlineParser;
    }

    /**
     * The main parsing function. Returns a parsed document AST.
     */
    public Document parse(CharSequence source) {
        BasedSequence input = source instanceof BasedSequence ? (BasedSequence) source : new SubSequence(source);
        int lineStart = 0;
        int lineBreak;
        int lineEOL;
        int lineEnd;
        lineNumber = 0;

        documentBlockParser.initializeDocument(options, input);
        inlineParser.initializeDocument(documentBlockParser.getBlock());

        currentPhase = ParserPhase.PARSE_BLOCKS;

        while ((lineBreak = Parsing.findLineBreak(input, lineStart)) != -1) {
            BasedSequence line = input.subSequence(lineStart, lineBreak);
            lineEOL = lineBreak;
            if (lineBreak + 1 < input.length() && input.charAt(lineBreak) == '\r' && input.charAt(lineBreak + 1) == '\n') {
                lineEnd = lineBreak + 2;
            } else {
                lineEnd = lineBreak + 1;
            }

            this.lineWithEOL = input.subSequence(lineStart, lineEnd);
            this.lineStart = lineStart;
            this.lineEOLIndex = lineEOL;
            this.lineEndIndex = lineEnd;
            incorporateLine(line);
            lineNumber++;
            lineStart = lineEnd;
        }

        if (input.length() > 0 && (lineStart == 0 || lineStart < input.length())) {
            this.lineWithEOL = input.subSequence(lineStart, input.length());
            this.lineStart = lineStart;
            this.lineEOLIndex = input.length();
            this.lineEndIndex = this.lineEOLIndex;
            incorporateLine(lineWithEOL);
            lineNumber++;
        }

        return finalizeAndProcess();
    }

    public Document parse(Reader input) throws IOException {
        BufferedReader bufferedReader;
        if (input instanceof BufferedReader) {
            bufferedReader = (BufferedReader) input;
        } else {
            bufferedReader = new BufferedReader(input);
        }

        StringBuilder file = new StringBuilder();
        char[] buffer = new char[16384];

        while (true) {
            int charsRead = bufferedReader.read(buffer);
            file.append(buffer, 0, charsRead);
            if (charsRead < buffer.length) break;
        }

        CharSequence source = new StringSequence(file.toString());
        return parse(source);
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public int getLineStart() {
        return lineStart;
    }

    public int getLineEndIndex() {
        return lineEndIndex;
    }

    @Override
    public BasedSequence getLine() {
        return line;
    }

    @Override
    public BasedSequence getLineWithEOL() {
        return lineWithEOL;
    }

    @Override
    public int getLineEolLength() {
        return lineEndIndex - lineEOLIndex;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public int getNextNonSpaceIndex() {
        return nextNonSpace;
    }

    @Override
    public int getColumn() {
        return column;
    }

    @Override
    public int getIndent() {
        return indent;
    }

    @Override
    public boolean isBlank() {
        return blank;
    }

    @Override
    public BlockParser getActiveBlockParser() {
        return activeBlockParsers.get(activeBlockParsers.size() - 1);
    }

    /**
     * Analyze a line of text and update the document appropriately. We parse markdown text by calling this on each
     * line of input, then finalizing the document.
     */
    private void incorporateLine(BasedSequence ln) {
        line = ln;
        index = 0;
        column = 0;
        columnIsInTab = false;

        // For each containing block, try to parse the associated line start.
        // Bail out on failure: container will point to the last matching block.
        // Set all_matched to false if not all containers match.
        // The document will always match, can be skipped
        int matches = 1;
        for (BlockParser blockParser : activeBlockParsers.subList(1, activeBlockParsers.size())) {
            findNextNonSpace();

            BlockContinue result = blockParser.tryContinue(this);
            if (result instanceof BlockContinueImpl) {
                BlockContinueImpl blockContinue = (BlockContinueImpl) result;
                if (blockContinue.isFinalize()) {
                    finalize(blockParser);
                    return;
                } else {
                    if (blockContinue.getNewIndex() != -1) {
                        setNewIndex(blockContinue.getNewIndex());
                    } else if (blockContinue.getNewColumn() != -1) {
                        setNewColumn(blockContinue.getNewColumn());
                    }
                    matches++;
                }
            } else {
                break;
            }
        }

        List<BlockParser> unmatchedBlockParsers = new ArrayList<>(activeBlockParsers.subList(matches, activeBlockParsers.size()));
        BlockParser lastMatchedBlockParser = activeBlockParsers.get(matches - 1);
        BlockParser blockParser = lastMatchedBlockParser;
        boolean allClosed = unmatchedBlockParsers.isEmpty();

        // Check to see if we've hit 2nd blank line; if so break out of list:
        if (isBlank() && isLastLineBlank(blockParser.getBlock())) {
            List<BlockParser> matchedBlockParsers = new ArrayList<>(activeBlockParsers.subList(0, matches));
            breakOutOfLists(matchedBlockParsers);
        }

        // Unless last matched container is a code block, try new container starts,
        // adding children to the last matched container:
        boolean tryBlockStarts = blockParser.getBlock() instanceof Paragraph || blockParser.isContainer();
        while (tryBlockStarts) {
            findNextNonSpace();

            // this is a little performance optimization:
            if (isBlank() || (indent < Parsing.CODE_BLOCK_INDENT && Parsing.isLetter(line, nextNonSpace))) {
                setNewIndex(nextNonSpace);
                break;
            }

            BlockStartImpl blockStart = findBlockStart(blockParser);
            if (blockStart == null) {
                setNewIndex(nextNonSpace);
                break;
            }

            if (!allClosed) {
                finalizeBlocks(unmatchedBlockParsers);
                allClosed = true;
            }

            if (blockStart.getNewIndex() != -1) {
                setNewIndex(blockStart.getNewIndex());
            } else if (blockStart.getNewColumn() != -1) {
                setNewColumn(blockStart.getNewColumn());
            }

            if (blockStart.isReplaceActiveBlockParser()) {
                removeActiveBlockParser();
            }

            for (BlockParser newBlockParser : blockStart.getBlockParsers()) {
                blockParser = addChild(newBlockParser);
                tryBlockStarts = newBlockParser.isContainer();
            }
        }

        // What remains at the offset is a text line. Add the text to the
        // appropriate block.

        // First check for a lazy paragraph continuation:
        if (!allClosed && !isBlank() && getActiveBlockParser() instanceof ParagraphParser) {
            // lazy paragraph continuation
            addLine();
        } else {
            // finalize any blocks not matched
            if (!allClosed) {
                finalizeBlocks(unmatchedBlockParsers);
            }
            propagateLastLineBlank(blockParser, lastMatchedBlockParser);

            if (!blockParser.isContainer()) {
                addLine();
            } else if (!isBlank()) {
                // inlineParser paragraph container for line
                addChild(new ParagraphParser());
                addLine();
            }
        }
    }

    private void findNextNonSpace() {
        int i = index;
        int cols = column;

        blank = true;
        while (i < line.length()) {
            char c = line.charAt(i);
            switch (c) {
                case ' ':
                    i++;
                    cols++;
                    continue;
                case '\t':
                    i++;
                    cols += (4 - (cols % 4));
                    continue;
            }
            blank = false;
            break;
        }

        nextNonSpace = i;
        nextNonSpaceColumn = cols;
        indent = nextNonSpaceColumn - column;
    }

    private void setNewIndex(int newIndex) {
        if (newIndex >= nextNonSpace) {
            // We can start from here, no need to calculate tab stops again
            index = nextNonSpace;
            column = nextNonSpaceColumn;
        }
        while (index < newIndex && index != line.length()) {
            advance();
        }
        // If we're going to an index as opposed to a column, we're never within a tab
        columnIsInTab = false;
    }

    private void setNewColumn(int newColumn) {
        if (newColumn >= nextNonSpaceColumn) {
            // We can start from here, no need to calculate tab stops again
            index = nextNonSpace;
            column = nextNonSpaceColumn;
        }
        while (column < newColumn && index != line.length()) {
            advance();
        }
        if (column > newColumn) {
            // Last character was a tab and we overshot our target
            index--;
            column = newColumn;
            columnIsInTab = true;
        } else {
            columnIsInTab = false;
        }
    }

    private void advance() {
        char c = line.charAt(index);
        if (c == '\t') {
            index++;
            column += Parsing.columnsToNextTabStop(column);
        } else {
            index++;
            column++;
        }
    }

    /**
     * Add line content to the active block parser. We assume it can accept lines -- that check should be done before
     * calling this.
     */
    private void addLine() {
        BasedSequence content = lineWithEOL.subSequence(index);
        if (columnIsInTab) {
            // Our column is in a partially consumed tab. Expand the remaining columns (to the next tab stop) to spaces.
            BasedSequence rest = content.subSequence(1);
            int spaces = Parsing.columnsToNextTabStop(column);
            StringBuilder sb = new StringBuilder(spaces + rest.length());
            for (int i = 0; i < spaces; i++) {
                sb.append(' ');
            }
            //sb.append(rest);
            content = new PrefixedSubSequence(sb.toString(), rest);
        }

        //getActiveBlockParser().addLine(content, content.baseSubSequence(lineEOL, lineEnd));
        //BasedSequence eol = content.baseSubSequence(lineEOL < lineEnd ? lineEnd - 1 : lineEnd, lineEnd).toMapped(EolCharacterMapper.INSTANCE);
        getActiveBlockParser().addLine(this, content);
    }

    private BlockStartImpl findBlockStart(BlockParser blockParser) {
        MatchedBlockParser matchedBlockParser = new MatchedBlockParserImpl(blockParser);
        for (BlockParserFactory blockParserFactory : blockParserFactories) {
            BlockStart result = blockParserFactory.tryStart(this, matchedBlockParser);
            if (result instanceof BlockStartImpl) {
                return (BlockStartImpl) result;
            }
        }
        return null;
    }

    /**
     * Finalize a block. Close it and do any necessary postprocessing, e.g. creating string_content from strings,
     * setting the 'tight' or 'loose' status of a list, and parsing the beginnings of paragraphs for reference
     * definitions.
     */
    private void finalize(BlockParser blockParser) {
        if (getActiveBlockParser() == blockParser) {
            deactivateBlockParser();
        }

        blockParser.closeBlock(this);

        blockParser.finalizeClosedBlock();
    }

    /**
     * Walk through a block & children recursively, parsing string content into inline content where appropriate.
     */
    private void processInlines() {
        for (BlockParser blockParser : allBlockParsers) {
            blockParser.parseInlines(inlineParser);
        }
    }

    @Override
    public boolean endsWithBlankLine(Node block) {
        while (block != null) {
            if (isLastLineBlank(block)) {
                return true;
            }
            if (block instanceof ListBlock || block instanceof ListItem) {
                block = block.getLastChild();
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * Break out of all containing lists, resetting the tip of the document to the parent of the highest list,
     * and finalizing all the lists. (This is used to implement the "two blank lines break of of all lists" feature.)
     */
    private void breakOutOfLists(List<BlockParser> blockParsers) {
        int lastList = -1;
        for (int i = blockParsers.size() - 1; i >= 0; i--) {
            BlockParser blockParser = blockParsers.get(i);
            if (blockParser.breakOutOnDoubleBlankLine()) {
                lastList = i;
            }
        }

        if (lastList != -1) {
            finalizeBlocks(blockParsers.subList(lastList, blockParsers.size()));
        }
    }

    /**
     * Add block of type tag as a child of the tip. If the tip can't  accept children, close and finalize it and try
     * its parent, and so on til we find a block that can accept children.
     */
    private <T extends BlockParser> T addChild(T blockParser) {
        while (!getActiveBlockParser().canContain(blockParser.getBlock())) {
            finalize(getActiveBlockParser());
        }

        getActiveBlockParser().getBlock().appendChild(blockParser.getBlock());
        activateBlockParser(blockParser);

        return blockParser;
    }

    private void activateBlockParser(BlockParser blockParser) {
        activeBlockParsers.add(blockParser);

        if (!allBlockParsers.contains(blockParser)) {
            // document parser has a null block at this point
            Block block = blockParser.getBlock();

            if (block != null) {
                if (blockParser instanceof ParagraphParser) {
                    allParagraphsList.add((Paragraph) block);
                }

                blockAdded(block, blockParser);
            }

            allBlockParsers.add(blockParser);
        }
    }

    @Override
    public void blockAdded(Block block, BlockParser blockParser) {
        if (!blockPreProcessorDependencies.isEmpty()) {
            Class<? extends Block> blockClass = block.getClass();
            if (blockPreProcessorDependencies.getBlockTypes().contains(blockClass)) {
                if (!allPreProcessBlocks.containsKey(blockClass)) allPreProcessBlocks.put(blockClass, new ArrayList<>());
                allPreProcessBlocks.get(blockClass).add(block);
            }
        }

        allBlocksParserMap.put(block, blockParser);
    }

    @Override
    public void removeBlock(Block block) {
        BlockParser blockParser = allBlocksParserMap.get(block);
        if (blockParser != null) {
            allBlockParsers.remove(blockParser);
        }

        allBlocksParserMap.remove(block);
        block.unlink();
    }

    private void deactivateBlockParser() {
        activeBlockParsers.remove(activeBlockParsers.size() - 1);
    }

    private void removeActiveBlockParser() {
        BlockParser old = getActiveBlockParser();
        deactivateBlockParser();

        allBlockParsers.remove(old);
        allBlocksParserMap.remove(old.getBlock());

        old.getBlock().unlink();
    }

    private void propagateLastLineBlank(BlockParser blockParser, BlockParser lastMatchedBlockParser) {
        if (isBlank() && blockParser.getBlock().getLastChild() != null) {
            setLastLineBlank(blockParser.getBlock().getLastChild(), true);
        }

        Block block = blockParser.getBlock();

        // Block quote lines are never blank as they start with >
        // and we don't count blanks in fenced code for purposes of tight/loose
        // lists or breaking out of lists. We also don't set lastLineBlank
        // on an empty list item.
        boolean lastLineBlank = isBlank() &&
                !(block instanceof BlockQuote ||
                        block instanceof FencedCodeBlock ||
                        (block instanceof ListItem &&
                                block.getFirstChild() == null &&
                                blockParser != lastMatchedBlockParser));

        // Propagate lastLineBlank up through parents
        Node node = blockParser.getBlock();
        while (node != null) {
            setLastLineBlank(node, lastLineBlank);
            node = node.getParent();
        }
    }

    private void setLastLineBlank(Node node, boolean value) {
        lastLineBlank.put(node, value);
    }

    @Override
    public boolean isLastLineBlank(Node node) {
        Boolean value = lastLineBlank.get(node);
        return value != null && value;
    }

    /**
     * Finalize blocks of previous line. Returns true.
     */
    private boolean finalizeBlocks(List<BlockParser> blockParsers) {
        for (int i = blockParsers.size() - 1; i >= 0; i--) {
            BlockParser blockParser = blockParsers.get(i);
            finalize(blockParser);
        }
        return true;
    }

    /**
     * pre-process paragraph
     *
     * @param block
     * @param processors
     */
    private void preProcessParagraph(Paragraph block, List<ParagraphPreProcessor> processors) {
        while (true) {
            boolean hadChanges = false;

            for (ParagraphPreProcessor processor : processors) {
                int pos = processor.preProcessBlock(block, this);

                if (pos > 0) {
                    hadChanges = true;

                    // skip leading blanks
                    BasedSequence blockChars = block.getChars();
                    BasedSequence contentChars = blockChars.subSequence(pos + blockChars.countChars(BasedSequenceImpl.WHITESPACE_CHARS, pos, blockChars.length()));

                    if (contentChars.isBlank()) {
                        // all used up
                        removeBlock(block);
                        return;
                    } else {
                        // skip lines that were removed
                        int iMax = block.getLineCount();
                        int i;
                        for (i = 0; i < iMax; i++) {
                            if (block.getLineChars(i).getEndOffset() > contentChars.getStartOffset()) break;
                        }

                        if (block.getLineChars(i).getEndOffset() == contentChars.getStartOffset()) {
                            // full lines removed
                            block.setContent(block, i, iMax);
                        } else {
                            // need to change the first line of the line list
                            ArrayList<BasedSequence> lines = new ArrayList<>(iMax - i);
                            lines.addAll(block.getContentLines().subList(i, iMax));
                            int start = contentChars.getStartOffset() - lines.get(0).getStartOffset();
                            lines.set(0, lines.get(0).subSequence(start));

                            // now we copy the indents
                            int[] indents = new int[iMax - i];
                            System.arraycopy(block.getLineIndents(), i, indents, 0, indents.length);
                            block.setContentLines(lines);
                            block.setLineIndents(indents);
                            block.setChars(contentChars);
                        }
                    }
                }
            }

            if (!hadChanges || processors.size() < 2) break;
        }
    }

    private void preProcessParagraphs() {
        // here we run preProcessing stages
        if (allParagraphsList.size() > 0) {
            List<List<ParagraphPreProcessor>> paragraphPreProcessorStages = new ArrayList<>(paragraphPreProcessorDependencies.getDependentStages().size());
            for (ParagraphPreProcessorDependencyStage factoryStage : paragraphPreProcessorDependencies.getDependentStages()) {
                ArrayList<ParagraphPreProcessor> stagePreProcessors = new ArrayList<>(factoryStage.dependents.size());
                for (ParagraphPreProcessorFactory factory : factoryStage.dependents) {
                    stagePreProcessors.add(factory.create(this));
                }
                paragraphPreProcessorStages.add(stagePreProcessors);
            }

            for (List<ParagraphPreProcessor> preProcessorStage : paragraphPreProcessorStages) {
                for (Paragraph paragraph : allParagraphsList) {
                    if (allBlocksParserMap.containsKey(paragraph)) {
                        preProcessParagraph(paragraph, preProcessorStage);
                    }
                }
            }
        }
    }

    private void preProcessBlocks() {
        // here we run preProcessing stages
        if (allPreProcessBlocks.size() > 0) {
            HashMap<BlockPreProcessorFactory, BlockPreProcessor> blockPreProcessors = new HashMap<>(blockPreProcessorDependencies.getDependentStages().size());

            for (BlockPreProcessorDependencyStage preProcessorStage : blockPreProcessorDependencies.getDependentStages()) {
                for (Map.Entry<Class<? extends Block>, List<BlockPreProcessorFactory>> entry : preProcessorStage.factoryMap.entrySet()) {
                    List<Block> blockList = allPreProcessBlocks.get(entry.getKey());
                    List<BlockPreProcessorFactory> factoryList = entry.getValue();

                    if (blockList != null && factoryList != null) {
                        for (Block block : blockList) {
                            if (allBlocksParserMap.containsKey(block)) {
                                for (BlockPreProcessorFactory factory : factoryList) {
                                    BlockPreProcessor blockPreProcessor = blockPreProcessors.get(factory);
                                    if (blockPreProcessor == null) {
                                        blockPreProcessor = factory.create(this);
                                        blockPreProcessors.put(factory, blockPreProcessor);
                                    }

                                    Block newBlock = blockPreProcessor.preProcess(this, block);

                                    if (newBlock != block) {
                                        // needs to be replaced
                                        BlockParser blockParser = allBlocksParserMap.get(block);
                                        if (blockParser != null) {
                                            allBlockParsers.remove(blockParser);
                                        }

                                        block.insertAfter(newBlock);
                                        blockAdded(newBlock, null);
                                        removeBlock(block);

                                        if (block.getClass() != newBlock.getClass()) {
                                            // class changed, we will rerun for this one
                                            break;
                                        }

                                        block = newBlock;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Document finalizeAndProcess() {
        finalizeBlocks(this.activeBlockParsers);

        // need to run block pre-processors at this point, before inline processing
        currentPhase = ParserPhase.PRE_PROCESS_PARAGRAPHS;
        this.preProcessParagraphs();
        currentPhase = ParserPhase.PRE_PROCESS_BLOCKS;
        this.preProcessBlocks();

        // can naw run inline processing
        currentPhase = ParserPhase.PARSE_INLINES;
        this.processInlines();

        currentPhase = ParserPhase.DONE;
        Document document = this.documentBlockParser.getBlock();
        inlineParser.finalizeDocument(document);
        return document;
    }

    private static class MatchedBlockParserImpl implements MatchedBlockParser {
        private final BlockParser matchedBlockParser;

        @Override
        public List<BasedSequence> getParagraphLines() {
            if (matchedBlockParser.isParagraphParser()) {
                return matchedBlockParser.getBlockContent().getLines();
            }
            return null;
        }

        public List<Integer> getParagraphEolLengths() {
            if (matchedBlockParser.isParagraphParser()) {
                return matchedBlockParser.getBlockContent().getLineIndents();
            }
            return null;
        }

        public MatchedBlockParserImpl(BlockParser matchedBlockParser) {
            this.matchedBlockParser = matchedBlockParser;
        }

        @Override
        public BlockParser getMatchedBlockParser() {
            return matchedBlockParser;
        }

        @Override
        public BasedSequence getParagraphContent() {
            if (matchedBlockParser.isParagraphParser()) {
                return matchedBlockParser.getBlockContent().getContents();
            }
            return null;
        }

        @Override
        public MutableDataHolder getParagraphDataHolder() {
            if (matchedBlockParser.isParagraphParser()) {
                return matchedBlockParser.getDataHolder();
            }
            return null;
        }
    }
}
