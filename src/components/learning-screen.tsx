import * as Speech from 'expo-speech';
import { ExpoSpeechRecognitionModule, useSpeechRecognitionEvent } from 'expo-speech-recognition';
import { useSQLiteContext } from 'expo-sqlite';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Modal, PanResponder, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { Colors, Spacing } from '@/constants/theme';
import { type Card, type LearningMode, getSettings, loadSessionCards, recordAttempt, setSessionIndex, startSession } from '@/data/database';
import { matchSpeech } from '@/domain/speech-matcher';
import { useColorScheme } from '@/hooks/use-color-scheme';

type Phase = 'loading' | 'ready' | 'speaking' | 'listening' | 'paused' | 'completed' | 'error';

export function LearningScreen({ deckId, mode: initialMode, visible, onClose }: { deckId: string; mode: LearningMode; visible: boolean; onClose: () => void }) {
  const db = useSQLiteContext();
  const scheme = useColorScheme();
  const colors = Colors[scheme === 'dark' ? 'dark' : 'light'];
  const [sessionId, setSessionId] = useState('');
  const [cards, setCards] = useState<Card[]>([]);
  const [index, setIndex] = useState(0);
  const [mode, setMode] = useState<LearningMode>(initialMode);
  const [phase, setPhase] = useState<Phase>('loading');
  const [transcript, setTranscript] = useState('');
  const [coverage, setCoverage] = useState(0);
  const [showExplanation, setShowExplanation] = useState(false);
  const [speechRate, setSpeechRate] = useState(1);
  const [advanceDelay, setAdvanceDelay] = useState(600);
  const operationRef = useRef(0);
  const completingRef = useRef(false);
  const stateRef = useRef({ sessionId, cards, index, mode, advanceDelay });
  stateRef.current = { sessionId, cards, index, mode, advanceDelay };
  const card = cards[index];

  const cancelAudio = useCallback(async () => {
    operationRef.current += 1;
    await Speech.stop();
    try { ExpoSpeechRecognitionModule.abort(); } catch { /* Native module can be unavailable in Expo Go. */ }
  }, []);

  const finishOrAdvance = useCallback(async (outcome: 'viewed' | 'read_completed' | 'skipped', resultCoverage?: number, endingMatched?: boolean) => {
    if (completingRef.current) return;
    completingRef.current = true;
    const current = stateRef.current;
    const currentCard = current.cards[current.index];
    if (!currentCard || !current.sessionId) return;
    await cancelAudio();
    await recordAttempt(db, { sessionId: current.sessionId, card: currentCard, mode: current.mode, outcome, coverage: resultCoverage, endingMatched });
    const nextIndex = current.index + 1;
    if (nextIndex >= current.cards.length) {
      await setSessionIndex(db, current.sessionId, nextIndex, 'completed');
      setPhase('completed'); setIndex(nextIndex); return;
    }
    await setSessionIndex(db, current.sessionId, nextIndex);
    setIndex(nextIndex); setTranscript(''); setCoverage(0); setShowExplanation(false); completingRef.current = false; setPhase('ready');
  }, [cancelAudio, db]);

  const startRecognition = useCallback(async (operation: number) => {
    try {
      const permission = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
      if (!permission.granted || operation !== operationRef.current) throw new Error('麦克风或语音识别权限未开启');
      setPhase('listening');
      ExpoSpeechRecognitionModule.start({ lang: 'zh-CN', interimResults: true, continuous: true, maxAlternatives: 1 });
    } catch (error) {
      setPhase('error'); Alert.alert('无法开始跟读', error instanceof Error ? error.message : '当前设备不可用', [{ text: '改用手动学习', onPress: () => { setMode('manual'); setPhase('ready'); } }]);
    }
  }, []);

  const speak = useCallback(async (autoListen = false) => {
    const currentCard = stateRef.current.cards[stateRef.current.index];
    if (!currentCard) return;
    await cancelAudio();
    const operation = operationRef.current;
    setTranscript(''); setCoverage(0); setPhase('speaking');
    Speech.speak(currentCard.speechText?.trim() || currentCard.content.trim(), {
      language: currentCard.language, rate: speechRate,
      onDone: () => { if (operation !== operationRef.current) return; autoListen ? void startRecognition(operation) : setPhase('ready'); },
      onError: () => { if (operation === operationRef.current) setPhase('error'); },
      onStopped: () => { if (operation === operationRef.current) setPhase('ready'); },
    });
  }, [cancelAudio, speechRate, startRecognition]);

  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results[0]?.transcript ?? '';
    const current = stateRef.current;
    const currentCard = current.cards[current.index];
    if (!currentCard || current.mode !== 'repeat') return;
    const result = matchSpeech(currentCard.speechText || currentCard.content, text);
    setTranscript(text); setCoverage(result.coverage);
    if (result.completed && event.isFinal) setTimeout(() => void finishOrAdvance('read_completed', result.coverage, result.endingMatched), current.advanceDelay);
  });
  useSpeechRecognitionEvent('error', (event) => {
    if (event.error === 'aborted') return;
    setPhase('error');
  });

  useEffect(() => {
    if (!visible) return;
    let active = true;
    void (async () => {
      setPhase('loading'); setMode(initialMode);
      const [session, settings] = await Promise.all([startSession(db, deckId, initialMode), getSettings(db)]);
      const sessionCards = await loadSessionCards(db, session.id);
      if (!active) return;
      completingRef.current = false; setSessionId(session.id); setCards(sessionCards); setIndex(session.currentIndex); setSpeechRate(settings.speechRate); setAdvanceDelay(settings.autoAdvanceDelayMs); setPhase('ready');
    })().catch(() => setPhase('error'));
    return () => { active = false; void cancelAudio(); };
  }, [cancelAudio, db, deckId, initialMode, visible]);

  useEffect(() => {
    if (!visible || mode !== 'repeat' || phase !== 'ready' || !card) return;
    void speak(true);
  }, [card, mode, phase, speak, visible]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      if (visible && next !== 'active') { void cancelAudio(); setPhase('paused'); if (sessionId) void setSessionIndex(db, sessionId, index, 'paused'); }
    });
    return () => subscription.remove();
  }, [cancelAudio, db, index, sessionId, visible]);

  const previous = async () => {
    if (index <= 0) return;
    await cancelAudio(); const next = index - 1; setIndex(next); setTranscript(''); setCoverage(0); setPhase('ready'); await setSessionIndex(db, sessionId, next);
  };
  const panResponder = PanResponder.create({ onMoveShouldSetPanResponder: (_, gesture) => Math.abs(gesture.dy) > 24, onPanResponderRelease: (_, gesture) => { if (gesture.dy < -60) void finishOrAdvance(mode === 'manual' ? 'viewed' : 'skipped'); else if (gesture.dy > 60) void previous(); } });
  const close = async () => { await cancelAudio(); if (sessionId && phase !== 'completed') await setSessionIndex(db, sessionId, index, 'paused'); onClose(); };

  return <Modal visible={visible} animationType="slide" presentationStyle="fullScreen" onRequestClose={() => void close()}>
    <ThemedView style={styles.screen}><SafeAreaView style={styles.safeArea}>
      <View style={styles.header}><Pressable onPress={() => void close()}><ThemedText type="link">关闭</ThemedText></Pressable><ThemedText>{Math.min(index + 1, cards.length)} / {cards.length}</ThemedText><Pressable onPress={async () => { if (phase === 'paused') { setPhase('ready'); await setSessionIndex(db, sessionId, index); } else { await cancelAudio(); setPhase('paused'); await setSessionIndex(db, sessionId, index, 'paused'); } }}><ThemedText type="link">{phase === 'paused' ? '继续' : '暂停'}</ThemedText></Pressable></View>
      <View style={[styles.progressTrack, { backgroundColor: colors.backgroundElement }]}><View style={[styles.progressFill, { width: `${cards.length ? (index / cards.length) * 100 : 0}%` }]} /></View>
      {phase === 'completed' ? <View style={styles.completed}><ThemedText type="title">完成本轮学习</ThemedText><ThemedText themeColor="textSecondary">共学习 {cards.length} 张卡片</ThemedText><Action label="返回卡组" onPress={() => void close()} /></View>
      : card ? <View style={styles.content} {...panResponder.panHandlers}>
        <ScrollView contentContainerStyle={[styles.learningCard, { backgroundColor: colors.backgroundElement }]}><ThemedText type="subtitle">{card.title}</ThemedText><ThemedText style={styles.body}>{card.content}</ThemedText>{card.explanation && <Pressable onPress={() => setShowExplanation((value) => !value)}><ThemedText type="linkPrimary">{showExplanation ? '收起解释' : '查看解释'}</ThemedText></Pressable>}{showExplanation && <ThemedText themeColor="textSecondary">{card.explanation}</ThemedText>}</ScrollView>
        <View style={styles.status}><ThemedText>{phaseLabel(phase, mode)}</ThemedText>{phase === 'listening' && <><ThemedText themeColor="textSecondary" numberOfLines={2}>{transcript || '请开始朗读…'}</ThemedText><View style={[styles.matchTrack, { backgroundColor: colors.backgroundElement }]}><View style={[styles.matchFill, { width: `${Math.round(coverage * 100)}%` }]} /></View></>}</View>
        <View style={styles.actions}><Action label="上一张" disabled={index === 0} onPress={() => void previous()} /><Action label={phase === 'speaking' ? '停止' : '重读'} onPress={() => phase === 'speaking' ? void cancelAudio().then(() => setPhase('ready')) : void speak(mode === 'repeat')} /><Action label={mode === 'repeat' ? '跳过' : index === cards.length - 1 ? '完成' : '下一张'} onPress={() => void finishOrAdvance(mode === 'repeat' ? 'skipped' : 'viewed')} /></View>
        <Pressable onPress={async () => { await cancelAudio(); setMode((value) => value === 'manual' ? 'repeat' : 'manual'); setPhase('ready'); }}><ThemedText type="linkPrimary" style={styles.mode}>{mode === 'manual' ? '切换到自动跟读' : '切换到手动学习'}</ThemedText></Pressable>
      </View> : <View style={styles.completed}><ThemedText>{phase === 'error' ? '加载失败，请返回重试' : '正在加载…'}</ThemedText></View>}
    </SafeAreaView></ThemedView>
  </Modal>;
}

function phaseLabel(phase: Phase, mode: LearningMode) { if (phase === 'speaking') return '🔊 正在朗读，请先听一遍'; if (phase === 'listening') return '🎙 轮到你读了'; if (phase === 'paused') return '已暂停'; if (phase === 'error') return '语音服务不可用，可切换到手动学习'; return mode === 'manual' ? '手动学习 · 上下滑动切换' : '自动跟读'; }
function Action({ label, onPress, disabled }: { label: string; onPress: () => void; disabled?: boolean }) { return <Pressable disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.action, (pressed || disabled) && { opacity: 0.4 }]}><ThemedText>{label}</ThemedText></Pressable>; }

const styles = StyleSheet.create({ screen: { flex: 1 }, safeArea: { flex: 1 }, header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: Spacing.four }, progressTrack: { height: 4, marginHorizontal: Spacing.four, borderRadius: 2, overflow: 'hidden' }, progressFill: { height: '100%', backgroundColor: '#4F46E5' }, content: { flex: 1, padding: Spacing.four, gap: Spacing.three }, learningCard: { flexGrow: 1, borderRadius: Spacing.four, padding: Spacing.four, justifyContent: 'center', gap: Spacing.four }, body: { fontSize: 24, lineHeight: 38 }, status: { minHeight: 76, alignItems: 'center', justifyContent: 'center', gap: Spacing.two }, actions: { flexDirection: 'row', justifyContent: 'space-between', gap: Spacing.two }, action: { flex: 1, alignItems: 'center', padding: Spacing.three, borderRadius: Spacing.three, backgroundColor: '#4F46E522' }, mode: { textAlign: 'center' }, completed: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: Spacing.four, padding: Spacing.four }, matchTrack: { width: '100%', height: 6, borderRadius: 3, overflow: 'hidden' }, matchFill: { height: '100%', backgroundColor: '#22C55E' } });
