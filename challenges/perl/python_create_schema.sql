CREATE TABLE `nasdaq_prices` (
  `ticker` varchar(5),
  `date` int(8),
  `open` decimal(8,4),
  `high` decimal(7,4),
  `low` decimal(8,4),
  `close` decimal(7,4),
  `vol` int(8)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
