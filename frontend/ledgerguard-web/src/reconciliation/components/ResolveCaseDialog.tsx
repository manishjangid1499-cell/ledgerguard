import React, { useState } from 'react';
import {
  Alert,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ReconciliationCaseResponse } from '../types/reconciliation.types';
import { useResolveCase } from '../hooks/useReconciliation';
import { getErrorMessage } from '../../shared/api/errorMessage';

interface ResolveCaseDialogProps {
  caseItem: ReconciliationCaseResponse | null;
  open: boolean;
  onClose: () => void;
  onSuccess?: () => void;
}

export const ResolveCaseDialog: React.FC<ResolveCaseDialogProps> = ({
  caseItem,
  open,
  onClose,
  onSuccess,
}) => {
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  const resolveMutation = useResolveCase();

  const hasControlChars = (str: string): boolean => {
    for (let i = 0; i < str.length; i++) {
      const code = str.charCodeAt(i);
      if ((code < 32 && code !== 9 && code !== 10 && code !== 13) || code === 127) {
        return true;
      }
    }
    return false;
  };

  if (!open || !caseItem) return null;

  const trimmedNote = note.trim();
  const containsControlChars = hasControlChars(note);
  const isTooShort = trimmedNote.length < 1;
  const isTooLong = trimmedNote.length > 1000;
  const isValid = !isTooShort && !isTooLong && !containsControlChars;

  const getValidationError = (): string | null => {
    if (!touched && trimmedNote.length === 0) return null;
    if (isTooShort) return 'Resolution note is required (at least 1 non-whitespace character).';
    if (isTooLong) return 'Resolution note cannot exceed 1000 characters.';
    if (containsControlChars) return 'Resolution note contains invalid control characters.';
    return null;
  };

  const validationError = getValidationError();

  const handleClose = () => {
    if (resolveMutation.isPending) return;
    setNote('');
    setTouched(false);
    resolveMutation.reset();
    onClose();
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!isValid) return;

    try {
      await resolveMutation.mutateAsync({
        caseId: caseItem.id,
        request: { resolutionNote: trimmedNote },
      });
      setNote('');
      setTouched(false);
      onSuccess?.();
      onClose();
    } catch {
      // error is captured in resolveMutation.error
    }
  };

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="resolve-case-dialog-title"
    >
      <form onSubmit={handleSubmit} noValidate>
        <DialogTitle id="resolve-case-dialog-title">
          Resolve Reconciliation Case
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5}>
            <DialogContentText>
              Marking this case as resolved records a manual resolution audit record.
              A mandatory operator resolution note must be provided explaining the root cause and remedy.
            </DialogContentText>

            <Stack spacing={0.5} sx={{ p: 1.5, bgcolor: 'background.default', borderRadius: 1 }}>
              <Typography variant="caption" color="text.secondary">
                Case ID: <Typography component="span" variant="caption" sx={{ fontFamily: 'monospace' }}>{caseItem.id}</Typography>
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Problem Type: <Typography component="span" variant="caption" sx={{ fontWeight: 600 }}>{caseItem.item.problemType}</Typography>
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Entity: <Typography component="span" variant="caption" sx={{ fontFamily: 'monospace' }}>{caseItem.item.entityType} ({caseItem.item.entityId})</Typography>
              </Typography>
            </Stack>

            {resolveMutation.isError && (
              <Alert severity="error">
                {getErrorMessage(resolveMutation.error, 'Failed to resolve reconciliation case.')}
              </Alert>
            )}

            <TextField
              label="Resolution Note"
              multiline
              rows={4}
              fullWidth
              required
              value={note}
              onChange={(e) => {
                setNote(e.target.value);
                if (!touched) setTouched(true);
              }}
              error={Boolean(validationError)}
              helperText={
                validationError ||
                `${trimmedNote.length}/1000 characters (explain investigation findings and corrective action taken)`
              }
              placeholder="Detail why this discrepancy occurred and how it was resolved..."
              slotProps={{
                htmlInput: {
                  maxLength: 1005,
                },
              }}
              disabled={resolveMutation.isPending}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button
            onClick={handleClose}
            variant="outlined"
            disabled={resolveMutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            color="primary"
            disabled={!isValid || resolveMutation.isPending}
            startIcon={resolveMutation.isPending ? <CircularProgress size={16} color="inherit" /> : null}
          >
            {resolveMutation.isPending ? 'Resolving...' : 'Confirm Resolution'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
};
