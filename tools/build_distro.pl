#!/usr/bin/env perl
# 쌍룡상사 런처용 distribution.json 만들기.
# - 기본은 원래 통합런처 배포 정보(tools/base-distribution.json)를 그대로 쓰고
# - 서버 id/이름/주소만 바꾸고, 쌍룡상사 UI 모드를 추가하고,
# - options.txt / servers.dat 는 우리 것으로 바꾼다 (MD5 를 비워서 처음 한 번만 깔림 → 유저가 바꾼 설정을 덮어쓰지 않음)
# 사용: perl tools/build_distro.pl   (주소 바꿀 땐 tools/ssangyong.json 의 address 만 고치고 다시 실행)
use strict; use warnings;
use JSON::PP; use Digest::MD5; use File::Basename; use File::Spec; use Cwd 'abs_path';

my $ROOT = dirname(dirname(abs_path($0)));
my $json = JSON::PP->new->utf8->canonical->pretty;
sub slurp { my $p = shift; open(my $h, '<:raw', $p) or die "$p: $!"; local $/; my $s = <$h>; close $h; return $s; }
sub md5 { my $p = shift; open(my $h, '<:raw', $p) or die "$p: $!"; my $d = Digest::MD5->new->addfile($h)->hexdigest; close $h; return $d; }

my $cfg  = $json->decode(slurp("$ROOT/tools/ssangyong.json"));
my $base = $json->decode(slurp("$ROOT/tools/base-distribution.json"));
my $RAW  = $cfg->{rawBase};

sub artifact {
    my ($rel, $with_md5) = @_;
    my $p = "$ROOT/$rel";
    my $a = { size => (-s $p) + 0, url => $RAW . $rel };
    $a->{MD5} = md5($p) if $with_md5;
    return $a;
}

# servers.dat (NBT, 압축 없음): 서버 하나 + 리소스팩 자동 허용(acceptTextures=1)
sub nbt_str { my $b = shift; utf8::encode($b) if utf8::is_utf8($b); return pack('n', length($b)) . $b; }
sub write_servers_dat {
    my ($path, $name, $ip) = @_;
    my $entry = "\x08" . nbt_str('name') . nbt_str($name)
              . "\x08" . nbt_str('ip') . nbt_str($ip)
              . "\x01" . nbt_str('acceptTextures') . "\x01"
              . "\x00";
    my $data = "\x0a" . nbt_str('')
             . "\x09" . nbt_str('servers') . "\x0a" . pack('N', 1) . $entry
             . "\x00";
    open(my $h, '>:raw', $path) or die "$path: $!"; print $h $data; close $h;
}
write_servers_dat("$ROOT/files/servers.dat", $cfg->{serverListName}, $cfg->{address});

my $d = $base;
$d->{version} = '1.0.0';
delete $d->{rss}; delete $d->{discord};
my $s = $d->{servers}[0];
$s->{id} = $cfg->{serverId};
$s->{name} = $cfg->{name};
$s->{description} = $cfg->{description};
$s->{address} = $cfg->{address};
delete $s->{discord};
$s->{mainServer} = JSON::PP::true;
$s->{autoconnect} = JSON::PP::true;

my %replace = map { $_ => 1 } ('options.txt', 'servers.dat', 'iris.properties', 'ComplementaryUnbound_r5.5.1.zip.txt');
my @mods = grep { !($_->{type} eq 'File' && $replace{$_->{id}}) } @{ $s->{modules} };

# 쌍룡상사 UI 모드
opendir(my $dh, "$ROOT/mods") or die $!;
for my $fn (sort grep { /\.jar$/ } readdir($dh)) {
    (my $ver = $fn) =~ s/^ssangyong-ui-//; $ver =~ s/\.jar$//;
    push @mods, { id => "com.ssangyong:ssangyong-ui:$ver\@jar", name => "\x{c30d}\x{b8e1}\x{c0c1}\x{c0ac} UI", type => 'FabricMod',
                  required => { value => JSON::PP::true }, artifact => artifact("mods/$fn", 1) };
}
closedir($dh);

# 설정 파일: MD5 없음 = 없을 때만 깔고, 이미 있으면 유저 설정 유지
for my $pair (['files/options.txt', 'options.txt'], ['files/servers.dat', 'servers.dat'],
              ['files/config/iris.properties', 'config/iris.properties'],
              ['files/shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt', 'shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt']) {
    my ($rel, $path) = @$pair;
    my $a = artifact($rel, 0); $a->{path} = $path;
    push @mods, { id => basename($path), name => basename($path), type => 'File', artifact => $a };
}
$s->{modules} = \@mods;
open(my $o, '>:raw', "$ROOT/distribution.json") or die $!; print $o $json->encode($d); close $o;
print "ok $s->{address} ", scalar(@mods), " modules\n";
