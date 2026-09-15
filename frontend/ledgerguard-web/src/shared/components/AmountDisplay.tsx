import { Typography } from '@mui/material';
import { formatMinorUnitsToInr } from '../utils/money';

interface AmountDisplayProps {
  amount: string;
  prefix?: string;
  color?: string;
}

export const AmountDisplay = ({ amount, prefix = '', color = 'primary.main' }: AmountDisplayProps) => {
  const formatted = prefix + formatMinorUnitsToInr(amount);
  const fontSize = formatted.length > 20
    ? { xs: '1.05rem', sm: '1.25rem', lg: '1.5rem' }
    : formatted.length > 16
    ? { xs: '1.4rem', sm: '1.65rem', lg: '1.8rem' }
    : { xs: '1.85rem', lg: '2.25rem' };
  return (
    <Typography component="p" variant="h4" sx={{ color, fontSize, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', my: 0.5 }}>
      {formatted}
    </Typography>
  );
};
