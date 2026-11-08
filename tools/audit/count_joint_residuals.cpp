// Exact independent counter for roots with no favorable pair of reductions.
// Build: c++ -O3 -std=c++20 tools/audit/count_joint_residuals.cpp -o /tmp/td18-joint-count
// This counts root classes, not sorting certificates or reduction endpoints.
// Totally unoriented roots are excluded and remain separate direct obligations.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
constexpr int MAX_N=19;
using Perm=std::array<int,MAX_N>;
struct Triple {int a,b,c;};
struct PairConstraint {Triple first,second;};
struct ConstraintGroup {Triple first;std::vector<Triple> seconds;};
int odd_cycles(const Perm& p,int n) {
 unsigned seen=0;int count=0;
 for(int x=0;x<n;++x)if(!(seen&(1u<<x))){int y=x,length=0;do{seen|=1u<<y;++length;y=p[y];}while(y!=x);count+=length&1;}
 return count;
}
bool ordered(int a,int b,int c,int n){return(b-a+n)%n<(c-a+n)%n;}
// Position in tau*beta, anchored at a, for an applicable tau=(a,b,c).
int after_position(int x,int a,int b,int c,int n){
 int d=(x-a+n)%n,db=(b-a+n)%n,dc=(c-a+n)%n;
 if(d<db)return d;if(d>=dc)return db+d-dc;return n-dc+d;
}
int gain(const Perm& p,Triple t){return(p[t.a]==t.b)+(p[t.b]==t.c)+(p[t.c]==t.a);}
Perm apply_move(const Perm& p,Triple t){Perm q=p;q[t.a]=p[t.c];q[t.b]=p[t.a];q[t.c]=p[t.b];return q;}
bool full_target(const Perm& p,int n){unsigned seen=0;int x=0;for(int i=0;i<n;++i){if(seen&(1u<<x))return false;seen|=1u<<x;x=p[(x+1)%n];}return x==0;}
bool unoriented(const Perm& p,int n){
 unsigned seen=0;
 for(int x=0;x<n;++x)if(!(seen&(1u<<x))){int y=x,increases=0,length=0;do{seen|=1u<<y;++length;increases+=p[y]>y;y=p[y];}while(y!=x);if(length>=3&&increases!=1)return false;}
 return true;
}
// Twisted reflection is f*p^{-1}*f, not ordinary image-array reversal.
bool canonical(const Perm& p,int n,const std::array<bool,MAX_N>& anchors){
 Perm inverse{};for(int i=0;i<n;++i)inverse[p[i]]=i;
 for(int a=0;a<n;++a)if(anchors[a])for(int reflection=0;reflection<2;++reflection)for(int j=0;j<n;++j){
  int value=reflection?(a-inverse[(a-j+n)%n]+n)%n:(p[(j+a)%n]-a+n)%n;
  if(value<p[j])return false;if(value>p[j])break;
 }
 return true;
}
struct Count{std::uint64_t nodes=0,leaves=0,unoriented_leaves=0,classes=0,no_qualifying=0;};
class Counter{
 int n;std::vector<int> parts;Perm omega{},inverse{},position{},cycle_start{},cycle_length{},previous_equal{},target{},partial_inverse{};
 int target_edges=0;
 std::array<std::vector<Triple>,MAX_N> fast;
 std::array<std::vector<PairConstraint>,MAX_N> pairs;
 std::array<std::vector<ConstraintGroup>,MAX_N> grouped_pairs;
 std::vector<unsigned char> cyclic_order,after_positions;
 std::vector<Triple> firsts;Count count;std::ostream* representatives;
 int triple_index(int a,int b,int c)const{return(a*n+b)*n+c;}
 bool constraints(int d)const{
  for(const auto t:fast[d])if(cyclic_order[triple_index(position[t.a],position[t.b],position[t.c])])return false;
  // Every group shares the first move and the depth at which its predicates
  // become determined. Its applicability and position transform are tested
  // once; the immutable lookup tables replace repeated modular arithmetic.
  for(const auto& group:grouped_pairs[d]){
   int index=triple_index(position[group.first.a],position[group.first.b],position[group.first.c]);
   if(!cyclic_order[index])continue;
   const auto* transformed=after_positions.data()+index*n;
   for(const auto t:group.seconds)if(cyclic_order[triple_index(
      transformed[position[t.a]],transformed[position[t.b]],transformed[position[t.c]])])return false;
  }return true;
 }
 bool add_target(int rank){
  int source=(position[rank]+n-1)%n,dest=position[omega[rank]];target[source]=dest;partial_inverse[dest]=position[rank];++target_edges;int x=dest;
  while(x!=source&&target[x]>=0)x=target[x];return x!=source||target_edges==n;
 }
 void remove_target(int rank){partial_inverse[position[omega[rank]]]=-1;target[(position[rank]+n-1)%n]=-1;--target_edges;}
 bool canonical_prefix(int d)const{
  // These are exactly the full canonical comparisons, stopped at the first
  // unknown image. Every assigned largest-cycle vertex is already a valid
  // possible origin. A strict comparison cannot be changed by completing
  // unknown images, so rejection preserves every least orbit representative.
  for(int rank=0;rank<=d;++rank)if(cycle_length[rank]==parts.front()){
   int a=position[rank];
   for(int reflection=0;reflection<2;++reflection){
    if(!reflection&&a==0)continue;
    for(int j=0;j<n;++j){
     int original=target[(j+n-1)%n];if(original<0)break;
     int image=reflection?partial_inverse[(a-j+n)%n]:target[(j+a+n-1)%n];
     if(image<0)break;
     int value=reflection?(a-image+n)%n:(image-a+n)%n;
     if(value<original)return false;if(value>original)break;
    }
   }
  }return true;
 }
 void leaf(){
  ++count.leaves;Perm p{};std::array<bool,MAX_N> anchors{};
  for(int r=0;r<n;++r){p[position[r]]=position[omega[r]];anchors[position[r]]=cycle_length[r]==parts.front();}
  if(unoriented(p,n)){++count.unoriented_leaves;return;}if(!canonical(p,n,anchors))return;
  ++count.classes;bool qualifying=false;
  for(const auto t:firsts)if(ordered(position[t.a],position[t.b],position[t.c],n)){qualifying=true;break;}
  if(!qualifying)++count.no_qualifying;
  if(representatives){for(int i=0;i<n;++i){if(i)*representatives<<' ';*representatives<<p[i];}*representatives<<'\n';}
 }
 void dfs(int d,unsigned used){
  ++count.nodes;if(d==n){leaf();return;}
  bool start=cycle_start[d]==d;int minimum=1;
  if(!start)minimum=position[cycle_start[d]]+1;else if(previous_equal[d]>=0)minimum=position[previous_equal[d]]+1;
  for(int x=minimum;x<n;++x)if(!(used&(1u<<x))){
   position[d]=x;
   bool edge_a=omega[d]<=d,edge_b=inverse[d]<d,okay=true,added_b=false;
   if(edge_a)okay=add_target(d);
   if(okay&&edge_b){okay=add_target(inverse[d]);added_b=true;}
   // A forced proper target cycle cannot be repaired by a later assignment.
   // Check this inexpensive condition before all two-move predicates.
   if(okay&&canonical_prefix(d)&&constraints(d))dfs(d+1,used|(1u<<x));
   if(added_b)remove_target(inverse[d]);if(edge_a)remove_target(d);
  }
 }
public:
 explicit Counter(std::vector<int> partition,std::ostream* output=nullptr):parts(std::move(partition)),representatives(output){
  std::sort(parts.rbegin(),parts.rend());n=std::accumulate(parts.begin(),parts.end(),0);
  if(n<3||n>MAX_N||parts.empty()||parts.back()<2)throw std::invalid_argument("Expected fixed-point-free partition of 3..19 symbols");
  int start=0,previous=-1,previous_length=-1;previous_equal.fill(-1);target.fill(-1);partial_inverse.fill(-1);
  for(int length:parts){
   if(length==previous_length)previous_equal[start]=previous;
   for(int j=0;j<length;++j){omega[start+j]=start+(j+1)%length;cycle_start[start+j]=start;cycle_length[start+j]=length;}
   previous=start;previous_length=length;start+=length;
  }
  for(int x=0;x<n;++x)inverse[omega[x]]=x;
  for(int x=0;x<n;++x){int y=omega[x],z=omega[y];if(x==z)continue;if(omega[z]==x&&(x>y||x>z))continue;fast[std::max({x,y,z})].push_back({x,y,z});}
  int odd=odd_cycles(omega,n);
  for(int a=0;a<n-2;++a)for(int b=a+1;b<n-1;++b)for(int c=b+1;c<n;++c)for(int reverse=0;reverse<2;++reverse){
   Triple first=reverse?Triple{a,c,b}:Triple{a,b,c};if(gain(omega,first)!=1)continue;
   Perm q=apply_move(omega,first);if(odd_cycles(q,n)!=odd+2)continue;firsts.push_back(first);
   for(int x=0;x<n;++x){int y=q[x],z=q[y];if(x==y||x==z||y==z)continue;if(q[z]==x&&(x>y||x>z))continue;pairs[std::max({a,b,c,x,y,z})].push_back({first,{x,y,z}});}
  }
  cyclic_order.resize(n*n*n);after_positions.resize(n*n*n*n);
  for(int a=0;a<n;++a)for(int b=0;b<n;++b)for(int c=0;c<n;++c){
   int index=triple_index(a,b,c);
   cyclic_order[index]=a!=b&&a!=c&&b!=c&&ordered(a,b,c,n);
   if(cyclic_order[index])for(int x=0;x<n;++x)after_positions[index*n+x]=after_position(x,a,b,c,n);
  }
  for(int d=0;d<n;++d)for(const auto& pair:pairs[d]){
   auto& groups=grouped_pairs[d];
   auto found=std::find_if(groups.begin(),groups.end(),[&](const auto& group){
    return group.first.a==pair.first.a&&group.first.b==pair.first.b&&group.first.c==pair.first.c;
   });
   if(found==groups.end()){groups.push_back({pair.first,{}});found=groups.end()-1;}
   found->seconds.push_back(pair.second);
  }
 }
 Count run(){position[0]=0;dfs(1,1);return count;}
};
// Independent complete-permutation validation with arbitrary transposition
// supports, without prefix predicates or the consecutive-triple criterion.
bool brute_residual(const Perm& p,int n,bool& no_qualifying){
 int odd=odd_cycles(p,n);no_qualifying=true;
 for(int a=0;a<n-2;++a)for(int b=a+1;b<n-1;++b)for(int c=b+1;c<n;++c)if(gain(p,{a,b,c})>=2)return false;
 for(int a=0;a<n-2;++a)for(int b=a+1;b<n-1;++b)for(int c=b+1;c<n;++c){
  Triple first{a,b,c};if(gain(p,first)!=1)continue;Perm q=apply_move(p,first);if(odd_cycles(q,n)!=odd+2)continue;no_qualifying=false;
  Perm beta{};int next=0;for(int x=0;x<a;++x)beta[next++]=x;for(int x=b;x<c;++x)beta[next++]=x;for(int x=a;x<b;++x)beta[next++]=x;for(int x=c;x<n;++x)beta[next++]=x;
  for(int i=0;i<n-2;++i)for(int j=i+1;j<n-1;++j)for(int k=j+1;k<n;++k){Triple second{beta[i],beta[j],beta[k]};if(q[second.a]==second.a||q[second.b]==second.b||q[second.c]==second.c)continue;if(gain(q,second)>=2)return false;}
 }return true;
}
void partitions(int remainder,int maximum,std::vector<int>& prefix,std::vector<std::vector<int>>& out){
 if(remainder==0){int evens=std::count_if(prefix.begin(),prefix.end(),[](int x){return x%2==0;});if(evens%2==0)out.push_back(prefix);return;}
 for(int x=std::min(remainder,maximum);x>=2;--x){prefix.push_back(x);partitions(remainder-x,x,prefix,out);prefix.pop_back();}
}
std::vector<std::vector<int>> all_partitions(int n){std::vector<std::vector<int>> out;std::vector<int> prefix;partitions(n,n,prefix,out);return out;}
void verify_small(int maximum){
 if(maximum>10)throw std::invalid_argument("Exhaustive validation is limited to N<=10");
 for(int n=3;n<=maximum;++n){
  std::uint64_t direct=0,direct_noqual=0,constrained=0,constrained_noqual=0;Perm p{};std::iota(p.begin(),p.begin()+n,0);std::array<bool,MAX_N> anchors;anchors.fill(true);
  do{bool fixed=false;for(int x=0;x<n;++x)if(p[x]==x){fixed=true;break;}if(fixed||!full_target(p,n)||unoriented(p,n)||!canonical(p,n,anchors))continue;bool noqual=false;if(brute_residual(p,n,noqual)){++direct;direct_noqual+=noqual;}}while(std::next_permutation(p.begin(),p.begin()+n));
  for(auto part:all_partitions(n)){Count c=Counter(part).run();constrained+=c.classes;constrained_noqual+=c.no_qualifying;}
  if(direct!=constrained||direct_noqual!=constrained_noqual)throw std::runtime_error("Small-domain comparison failed at N="+std::to_string(n)+": direct="+std::to_string(direct)+", constrained="+std::to_string(constrained));
  std::cout<<"{\"format\":\"td18-joint-small-check-v1\",\"n\":"<<n<<",\"classes\":"<<direct<<",\"no_qualifying_first_move\":"<<direct_noqual<<",\"agrees\":true}"<<std::endl;
 }
}
std::vector<int> parse_partition(std::string s){std::vector<int> out;size_t start=0;do{size_t end=s.find(',',start);out.push_back(std::stoi(s.substr(start,end-start)));if(end==std::string::npos)break;start=end+1;}while(true);return out;}
}
int main(int argc,char** argv){
 try{
  int n=19,verify=0;std::vector<int> selected;std::string representatives_path;
  for(int i=1;i<argc;++i){std::string arg=argv[i];if(arg.starts_with("--n="))n=std::stoi(arg.substr(4));else if(arg.starts_with("--partition="))selected=parse_partition(arg.substr(12));else if(arg.starts_with("--verify-small="))verify=std::stoi(arg.substr(15));else if(arg.starts_with("--representatives="))representatives_path=arg.substr(18);else throw std::invalid_argument("Unknown argument: "+arg);}
  if(verify){verify_small(verify);return 0;}if(n<3||n>MAX_N)throw std::invalid_argument("N must be 3..19");
  auto parts=selected.empty()?all_partitions(n):std::vector<std::vector<int>>{selected};
  if(!selected.empty())n=std::accumulate(selected.begin(),selected.end(),0);
  std::ofstream reps;if(!representatives_path.empty()){reps.open(representatives_path);if(!reps)throw std::runtime_error("Cannot open representatives output");reps<<"TD18-JOINT-RESIDUALS 1 N="<<n<<" scope=oriented\n";}
  std::uint64_t total=0;
  for(const auto& part:parts){
   auto started=std::chrono::steady_clock::now();Count c=Counter(part,reps.is_open()?&reps:nullptr).run();total+=c.classes;
   double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
   std::cout<<"{\"format\":\"td18-joint-residual-count-v1\",\"partition\":[";for(size_t i=0;i<part.size();++i){if(i)std::cout<<',';std::cout<<part[i];}
   std::cout<<"],\"oriented_root_classes\":"<<c.classes<<",\"no_qualifying_first_move\":"<<c.no_qualifying<<",\"qualifying_but_no_favorable_pair\":"<<c.classes-c.no_qualifying<<",\"prefix_nodes\":"<<c.nodes<<",\"realizable_leaves\":"<<c.leaves<<",\"ignored_unoriented_leaves\":"<<c.unoriented_leaves<<",\"elapsed_seconds\":"<<elapsed<<"}"<<std::endl;
  }
  if(reps.is_open()){reps<<"COUNT "<<total<<'\n';reps.close();if(!reps)throw std::runtime_error("Representatives output failed");}
 }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 1;}
}
