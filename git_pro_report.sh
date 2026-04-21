USERNAME="cnIshant007"

# Auto-generate date range
FROM=“2025-10-01"
TO=“2025-10-31"

echo "📅 Local Git Work Summary"
echo "========================="

git log --since="$FROM" --until="$TO" \
--pretty=format:"%ad|%s" --date=short \
| sort \
| awk -F'|' '
{
  if ($1 != last_date) {
    print "\n📅 Date: " $1
    last_date = $1
  }
  print "- " $2
}
'

echo "✅ Done!"