import React from 'react';
import { Box, Button, Container, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useSearchParams } from 'react-router-dom';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined';
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined';
import BuildCircleOutlinedIcon from '@mui/icons-material/BuildCircleOutlined';
import { RunsView } from '../components/RunsView';
import { CasesView } from '../components/CasesView';
import { SnapshotRepairView } from '../components/SnapshotRepairView';
import { RunTriggerDialog } from '../components/RunTriggerDialog';
import { RunDetailDialog } from '../components/RunDetailDialog';

type ReconciliationTab = 'runs' | 'cases' | 'repair';

export const ReconciliationPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const currentTab = (searchParams.get('tab') as ReconciliationTab) || 'runs';

  const [isTriggerOpen, setIsTriggerOpen] = React.useState(false);
  const [createdRunId, setCreatedRunId] = React.useState<string | null>(null);

  const handleTabChange = (_: React.SyntheticEvent, newTab: ReconciliationTab) => {
    setSearchParams({ tab: newTab });
  };

  return (
    <Container maxWidth="lg">
      <Stack
        spacing={2}
        sx={{
          flexDirection: { xs: 'column', sm: 'row' },
          justifyContent: 'space-between',
          alignItems: { xs: 'flex-start', sm: 'center' },
          mb: 3,
        }}
      >
        <Box>
          <Typography
            variant="h4"
            component="h1"
            color="primary.main"
            sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, mb: 0.5, fontWeight: 700 }}
          >
            Reconciliation Workspace
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Continuous verification of journal balances, balance snapshot integrity, and external PSP settlements.
          </Typography>
        </Box>

        <Button
          variant="contained"
          color="primary"
          startIcon={<PlayArrowIcon />}
          onClick={() => setIsTriggerOpen(true)}
        >
          Start Reconciliation
        </Button>
      </Stack>

      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
        <Tabs
          value={currentTab}
          onChange={handleTabChange}
          aria-label="Reconciliation workspace views"
        >
          <Tab
            value="runs"
            label="Reconciliation Runs"
            icon={<HistoryOutlinedIcon />}
            iconPosition="start"
            id="tab-runs"
            aria-controls="panel-runs"
          />
          <Tab
            value="cases"
            label="Investigation Cases"
            icon={<AssignmentOutlinedIcon />}
            iconPosition="start"
            id="tab-cases"
            aria-controls="panel-cases"
          />
          <Tab
            value="repair"
            label="Snapshot Repair"
            icon={<BuildCircleOutlinedIcon />}
            iconPosition="start"
            id="tab-repair"
            aria-controls="panel-repair"
          />
        </Tabs>
      </Box>

      <Box role="tabpanel" id={`panel-${currentTab}`} aria-labelledby={`tab-${currentTab}`}>
        {currentTab === 'runs' && <RunsView />}
        {currentTab === 'cases' && <CasesView />}
        {currentTab === 'repair' && <SnapshotRepairView />}
      </Box>

      <RunTriggerDialog
        open={isTriggerOpen}
        onClose={() => setIsTriggerOpen(false)}
        onSuccess={(run) => {
          if (run) setCreatedRunId(run.id);
        }}
      />

      <RunDetailDialog
        runId={createdRunId}
        open={Boolean(createdRunId)}
        onClose={() => setCreatedRunId(null)}
      />
    </Container>
  );
};
