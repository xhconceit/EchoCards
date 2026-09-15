import { useFocusEffect } from 'expo-router';
import * as Speech from 'expo-speech';
import { useSQLiteContext } from 'expo-sqlite';
import { useCallback, useState } from 'react';
import { Alert, FlatList, Modal, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { LearningScreen } from '@/components/learning-screen';
import { BottomTabInset, Colors, Spacing } from '@/constants/theme';
import { type Card, type DeckSummary, deleteCard, deleteDeck, listCards, listDecks, moveCard, saveCard, saveDeck } from '@/data/database';
import { useColorScheme } from '@/hooks/use-color-scheme';

type EditorState = { kind: 'deck'; deck?: DeckSummary } | { kind: 'card'; card?: Card } | null;

export default function DecksScreen() {
  const db = useSQLiteContext();
  const scheme = useColorScheme();
  const colors = Colors[scheme === 'dark' ? 'dark' : 'light'];
  const [decks, setDecks] = useState<DeckSummary[]>([]);
  const [selectedDeck, setSelectedDeck] = useState<DeckSummary | null>(null);
  const [cards, setCards] = useState<Card[]>([]);
  const [editor, setEditor] = useState<EditorState>(null);
  const [learningMode, setLearningMode] = useState<'manual' | 'repeat' | null>(null);

  const refreshDecks = useCallback(async () => {
    const next = await listDecks(db);
    setDecks(next);
  }, [db]);
  const refreshCards = useCallback(async () => {
    if (selectedDeck) setCards(await listCards(db, selectedDeck.id));
  }, [db, selectedDeck]);

  useFocusEffect(useCallback(() => { void refreshDecks(); }, [refreshDecks]));
  useFocusEffect(useCallback(() => { void refreshCards(); }, [refreshCards]));

  if (selectedDeck) {
    return <ThemedView style={styles.screen}>
      <SafeAreaView style={styles.safeArea} edges={['top']}>
        <View style={styles.header}>
          <Pressable onPress={() => setSelectedDeck(null)} hitSlop={12}><ThemedText type="link">‹ 卡组</ThemedText></Pressable>
          <View style={styles.headerTitle}><ThemedText type="subtitle" numberOfLines={1}>{selectedDeck.title}</ThemedText><ThemedText type="small" themeColor="textSecondary">{cards.length} 张卡片</ThemedText></View>
          <AddButton colors={colors} onPress={() => setEditor({ kind: 'card' })} />
        </View>
        <View style={styles.learnActions}>
          <Pressable disabled={!cards.length} style={({ pressed }) => [styles.learnButton, { backgroundColor: colors.backgroundElement }, (!cards.length || pressed) && { opacity: 0.45 }]} onPress={() => setLearningMode('manual')}><ThemedText style={styles.semibold}>手动学习</ThemedText></Pressable>
          <Pressable disabled={!cards.length} style={({ pressed }) => [styles.learnButton, styles.repeatButton, (!cards.length || pressed) && { opacity: 0.45 }]} onPress={() => setLearningMode('repeat')}><ThemedText style={styles.repeatText}>自动跟读</ThemedText></Pressable>
        </View>
        <FlatList data={cards} keyExtractor={(item) => item.id} contentContainerStyle={[styles.list, cards.length === 0 && styles.emptyList]}
          ListEmptyComponent={<EmptyState title="还没有卡片" subtitle="添加第一张知识卡片，开始构建你的卡组。" action="添加卡片" onPress={() => setEditor({ kind: 'card' })} />}
          renderItem={({ item, index }) => <Pressable style={[styles.card, { backgroundColor: colors.backgroundElement }]} onPress={() => setEditor({ kind: 'card', card: item })}>
            <View style={styles.cardBody}><ThemedText type="small" themeColor="textSecondary">{index + 1}</ThemedText><ThemedText style={styles.semibold}>{item.title}</ThemedText><ThemedText numberOfLines={2} themeColor="textSecondary">{item.content}</ThemedText></View>
            <View style={styles.orderButtons}>
              <SmallButton label="↑" disabled={index === 0} onPress={async () => { await moveCard(db, cards, item.id, -1); await refreshCards(); }} />
              <SmallButton label="↓" disabled={index === cards.length - 1} onPress={async () => { await moveCard(db, cards, item.id, 1); await refreshCards(); }} />
              <SmallButton label="删除" danger onPress={() => confirmDelete('删除卡片？', `“${item.title}”将被永久删除。`, async () => { await deleteCard(db, item.id); await refreshCards(); await refreshDecks(); })} />
            </View>
          </Pressable>} />
      </SafeAreaView>
      <EditorModal editor={editor} deckId={selectedDeck.id} onClose={() => setEditor(null)} onSaved={async () => { setEditor(null); await refreshCards(); await refreshDecks(); }} />
      <LearningScreen deckId={selectedDeck.id} mode={learningMode ?? 'manual'} visible={learningMode !== null} onClose={() => setLearningMode(null)} />
    </ThemedView>;
  }

  return <ThemedView style={styles.screen}>
    <SafeAreaView style={styles.safeArea} edges={['top']}>
      <View style={styles.header}><View style={styles.headerTitle}><ThemedText type="title">知声卡</ThemedText><ThemedText themeColor="textSecondary">把知识变成可以开口练习的卡片</ThemedText></View><AddButton colors={colors} onPress={() => setEditor({ kind: 'deck' })} /></View>
      <FlatList data={decks} keyExtractor={(item) => item.id} contentContainerStyle={[styles.list, decks.length === 0 && styles.emptyList]}
        ListEmptyComponent={<EmptyState title="创建你的第一个卡组" subtitle="可以从诗词、外语或任何想记住的内容开始。" action="新建卡组" onPress={() => setEditor({ kind: 'deck' })} />}
        renderItem={({ item }) => <Pressable style={[styles.deck, { backgroundColor: colors.backgroundElement }]} onPress={() => setSelectedDeck(item)}>
          <View style={styles.cardBody}><ThemedText type="subtitle">{item.title}</ThemedText>{!!item.description && <ThemedText themeColor="textSecondary" numberOfLines={2}>{item.description}</ThemedText>}<ThemedText type="small" themeColor="textSecondary">{item.cardCount} 张卡片</ThemedText></View>
          <View style={styles.orderButtons}><SmallButton label="编辑" onPress={() => setEditor({ kind: 'deck', deck: item })} /><SmallButton label="删除" danger onPress={() => confirmDelete('删除卡组？', `“${item.title}”及其中 ${item.cardCount} 张卡片将被永久删除。`, async () => { await deleteDeck(db, item.id); await refreshDecks(); })} /></View>
        </Pressable>} />
    </SafeAreaView>
    <EditorModal editor={editor} onClose={() => setEditor(null)} onSaved={async () => { setEditor(null); await refreshDecks(); }} />
  </ThemedView>;
}

function EditorModal({ editor, deckId, onClose, onSaved }: { editor: EditorState; deckId?: string; onClose: () => void; onSaved: () => Promise<void> }) {
  const db = useSQLiteContext();
  const scheme = useColorScheme();
  const colors = Colors[scheme === 'dark' ? 'dark' : 'light'];
  const isDeck = editor?.kind === 'deck';
  const existing = editor?.kind === 'deck' ? editor.deck : editor?.card;
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [speechText, setSpeechText] = useState('');
  const [extra, setExtra] = useState('');
  const reset = () => {
    setTitle(existing?.title ?? ''); setContent(editor?.kind === 'card' ? editor.card?.content ?? '' : '');
    setSpeechText(editor?.kind === 'card' ? editor.card?.speechText ?? '' : '');
    setExtra(editor?.kind === 'deck' ? editor.deck?.description ?? '' : editor?.kind === 'card' ? editor.card?.explanation ?? '' : '');
  };

  return <Modal visible={!!editor} animationType="slide" presentationStyle="pageSheet" onShow={reset} onRequestClose={onClose}>
    <ScrollView style={{ backgroundColor: colors.background }} contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
      <View style={styles.modalHeader}><Pressable onPress={onClose}><ThemedText type="link">取消</ThemedText></Pressable><ThemedText type="subtitle">{existing ? '编辑' : '新建'}{isDeck ? '卡组' : '卡片'}</ThemedText><Pressable onPress={async () => {
        try {
          if (editor?.kind === 'deck') await saveDeck(db, { id: editor.deck?.id, title, description: extra });
          else if (editor?.kind === 'card' && deckId) await saveCard(db, { id: editor.card?.id, deckId, title, content, speechText, explanation: extra });
          await onSaved();
        } catch (error) { Alert.alert('无法保存', error instanceof Error ? error.message : '请检查输入内容'); }
      }}><ThemedText type="linkPrimary">保存</ThemedText></Pressable></View>
      <Field label={isDeck ? '名称' : '标题'} value={title} onChangeText={setTitle} placeholder={isDeck ? '例如：唐诗三百首' : '例如：静夜思'} colors={colors} />
      {!isDeck && <Field label="正文" value={content} onChangeText={setContent} placeholder="卡片展示和默认朗读的内容" multiline colors={colors} />}
      {!isDeck && <Field label="跟读文本（可选）" value={speechText} onChangeText={setSpeechText} placeholder="留空时使用正文" multiline colors={colors} />}
      {!isDeck && <Pressable style={styles.previewButton} onPress={() => { void Speech.stop(); Speech.speak(speechText.trim() || content.trim(), { language: 'zh-CN' }); }}><ThemedText type="linkPrimary">试听跟读文本</ThemedText></Pressable>}
      <Field label={isDeck ? '说明（可选）' : '补充解释（可选）'} value={extra} onChangeText={setExtra} placeholder="添加一些背景信息" multiline colors={colors} />
    </ScrollView>
  </Modal>;
}

function Field({ label, colors, ...props }: React.ComponentProps<typeof TextInput> & { label: string; colors: typeof Colors.light | typeof Colors.dark }) {
  return <View style={styles.field}><ThemedText style={styles.semibold}>{label}</ThemedText><TextInput {...props} placeholderTextColor={colors.textSecondary} style={[styles.input, props.multiline && styles.textarea, { color: colors.text, backgroundColor: colors.backgroundElement }]} /></View>;
}
function AddButton({ colors, onPress }: { colors: typeof Colors.light | typeof Colors.dark; onPress: () => void }) { return <Pressable style={[styles.addButton, { backgroundColor: colors.text }]} onPress={onPress}><ThemedText style={{ color: colors.background }}>＋</ThemedText></Pressable>; }
function SmallButton({ label, onPress, disabled, danger }: { label: string; onPress: () => void; disabled?: boolean; danger?: boolean }) { return <Pressable disabled={disabled} onPress={onPress} hitSlop={8} style={({ pressed }) => [styles.smallButton, (pressed || disabled) && { opacity: 0.35 }]}><ThemedText type="small" style={danger ? styles.danger : undefined}>{label}</ThemedText></Pressable>; }
function EmptyState({ title, subtitle, action, onPress }: { title: string; subtitle: string; action: string; onPress: () => void }) { return <View style={styles.empty}><ThemedText type="subtitle">{title}</ThemedText><ThemedText themeColor="textSecondary" style={styles.center}>{subtitle}</ThemedText><Pressable style={styles.primaryButton} onPress={onPress}><ThemedText style={styles.primaryText}>{action}</ThemedText></Pressable></View>; }
function confirmDelete(title: string, message: string, action: () => Promise<void>) { Alert.alert(title, message, [{ text: '取消', style: 'cancel' }, { text: '删除', style: 'destructive', onPress: () => void action() }]); }

const styles = StyleSheet.create({
  screen: { flex: 1 }, safeArea: { flex: 1 }, header: { flexDirection: 'row', alignItems: 'center', gap: Spacing.three, padding: Spacing.four }, headerTitle: { flex: 1, gap: Spacing.one },
  addButton: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' }, list: { paddingHorizontal: Spacing.four, paddingBottom: BottomTabInset + Spacing.four, gap: Spacing.three }, emptyList: { flexGrow: 1, justifyContent: 'center' },
  deck: { borderRadius: Spacing.four, padding: Spacing.four, gap: Spacing.three }, card: { borderRadius: Spacing.three, padding: Spacing.three, flexDirection: 'row', gap: Spacing.two }, cardBody: { flex: 1, gap: Spacing.two }, orderButtons: { flexDirection: 'row', alignItems: 'center', gap: Spacing.one },
  learnActions: { flexDirection: 'row', gap: Spacing.three, paddingHorizontal: Spacing.four, paddingBottom: Spacing.three }, learnButton: { flex: 1, alignItems: 'center', padding: Spacing.three, borderRadius: Spacing.three }, repeatButton: { backgroundColor: '#4F46E5' }, repeatText: { color: '#FFFFFF', fontWeight: '600' },
  smallButton: { paddingHorizontal: Spacing.two, paddingVertical: Spacing.two }, danger: { color: '#D64045' }, empty: { alignItems: 'center', gap: Spacing.three, padding: Spacing.four }, center: { textAlign: 'center' }, primaryButton: { backgroundColor: '#4F46E5', paddingHorizontal: Spacing.four, paddingVertical: Spacing.three, borderRadius: 999 }, primaryText: { color: '#FFFFFF', fontWeight: '600' },
  form: { padding: Spacing.four, gap: Spacing.four }, modalHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: Spacing.two }, field: { gap: Spacing.two }, semibold: { fontWeight: '600' }, input: { borderRadius: Spacing.three, paddingHorizontal: Spacing.three, paddingVertical: Spacing.three, fontSize: 16 }, textarea: { minHeight: 112, textAlignVertical: 'top' },
  previewButton: { alignSelf: 'flex-start', paddingVertical: Spacing.two },
});
