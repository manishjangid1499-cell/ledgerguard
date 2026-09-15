import { useEffect, useRef, useState } from 'react';
import { Box, IconButton, Tooltip } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';

export const CopyButton = ({ value, label }: { value: string; label: string }) => {
  const [feedback, setFeedback] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  return (
    <Box component="span" sx={{ display: 'inline-flex', flexShrink: 0 }}>
      <Tooltip title={feedback || `Copy ${label}`}>
        <IconButton size="small" aria-label={`Copy ${label}`} onClick={async (event) => {
          event.stopPropagation();
          clearTimeout(timer.current);
          try {
            await navigator.clipboard.writeText(value);
            setFeedback('Copied');
          } catch {
            setFeedback('Could not copy. Select and copy the ID manually.');
          }
          timer.current = setTimeout(() => setFeedback(''), 3000);
        }}>
          {feedback === 'Copied' ? <CheckIcon fontSize="small" color="secondary" /> : <ContentCopyIcon fontSize="small" />}
        </IconButton>
      </Tooltip>
      <span role="status" className="sr-only">{feedback}</span>
    </Box>
  );
};
