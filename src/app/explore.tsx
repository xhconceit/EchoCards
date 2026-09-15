import { useFocusEffect } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useCallback, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { Colors, Spacing } from '@/constants/theme';
import { type LearningMode, getSettings, saveSettings } from '@/data/database';
import { useColorScheme } from '@/hooks/use-color-scheme';

export default function SettingsScreen() {
  const db = useSQLiteContext();
  const scheme = useColorScheme();
  const colors = Colors[scheme === 'dark' ? 'dark' : 'light'];
  const [mode, setMode] = useState<LearningMode>('manual');
  const [rate, setRate] = useState(1);
  const [delay, setDelay] = useState(600);
  const [saved, setSaved] = useState(false);

  useFocusEffect(useCallback(() => { void getSettings(db).then((settings) => { setMode(settings.defaultMode); setRate(settings.speechRate); setDelay(settings.autoAdvanceDelayMs); }); }, [db]));
  const persist = async (nextMode = mode, nextRate = rate, nextDelay = delay) => {
    setMode(nextMode); setRate(nextRate); setDelay(nextDelay); setSaved(false);
    await saveSettings(db, { defaultMode: nextMode, speechRate: nextRate, autoAdvanceDelayMs: nextDelay });
    setSaved(true); setTimeout(() => setSaved(false), 1200);
  };

  return <ThemedView style={styles.screen}><SafeAreaView style={styles.container}>
    <ThemedText type="title">设置</ThemedText>
    <SettingCard colors={colors} title="默认学习模式">
      <View style={styles.row}><Choice label="手动学习" selected={mode === 'manual'} onPress={() => void persist('manual')} /><Choice label="自动跟读" selected={mode === 'repeat'} onPress={() => void persist('repeat')} /></View>
    </SettingCard>
    <SettingCard colors={colors} title="朗读速度"><Stepper value={`${rate.toFixed(1)}×`} minus={() => void persist(mode, Math.max(0.5, rate - 0.1), delay)} plus={() => void persist(mode, Math.min(2, rate + 0.1), delay)} /></SettingCard>
    <SettingCard colors={colors} title="完成后等待"><Stepper value={`${delay} ms`} minus={() => void persist(mode, rate, Math.max(0, delay - 100))} plus={() => void persist(mode, rate, Math.min(3000, delay + 100))} /></SettingCard>
    <ThemedText type="small" themeColor="textSecondary">自动跟读需要麦克风和语音识别权限，并需要 Development Build。语音服务不可用时仍可手动学习。</ThemedText>
    {saved && <ThemedText type="small" style={styles.saved}>已保存</ThemedText>}
  </SafeAreaView></ThemedView>;
}

function SettingCard({ colors, title, children }: { colors: typeof Colors.light | typeof Colors.dark; title: string; children: React.ReactNode }) { return <View style={[styles.card, { backgroundColor: colors.backgroundElement }]}><ThemedText style={styles.semibold}>{title}</ThemedText>{children}</View>; }
function Choice({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) { return <Pressable onPress={onPress} style={[styles.choice, selected && styles.selected]}><ThemedText style={selected && styles.selectedText}>{label}</ThemedText></Pressable>; }
function Stepper({ value, minus, plus }: { value: string; minus: () => void; plus: () => void }) { return <View style={styles.stepper}><Pressable onPress={minus} style={styles.step}><ThemedText>−</ThemedText></Pressable><ThemedText>{value}</ThemedText><Pressable onPress={plus} style={styles.step}><ThemedText>＋</ThemedText></Pressable></View>; }

const styles = StyleSheet.create({ screen: { flex: 1 }, container: { flex: 1, padding: Spacing.four, gap: Spacing.four }, card: { padding: Spacing.four, borderRadius: Spacing.four, gap: Spacing.three }, semibold: { fontWeight: '600' }, row: { flexDirection: 'row', gap: Spacing.two }, choice: { flex: 1, alignItems: 'center', padding: Spacing.three, borderRadius: Spacing.three }, selected: { backgroundColor: '#4F46E5' }, selectedText: { color: '#FFFFFF' }, stepper: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }, step: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', backgroundColor: '#4F46E522', borderRadius: 22 }, saved: { color: '#16A34A', textAlign: 'center' } });
